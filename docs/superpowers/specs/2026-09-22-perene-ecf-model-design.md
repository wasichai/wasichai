# Perené ECF → GeoForge Core: modelo de Custom Objects

Fecha: 2026-09-23 · Estado: aprobado · Entregable: `examples/perene/`

## Context

`~/Downloads/120302_MD_Perene_ECF.gdb` es el catastro fiscal (ECF) del distrito de Perené (ubigeo
120302, Junín) en EPSG:32718. Queremos montarlo en GeoForge como Custom Objects relacionados para que
GIS‑XP lo muestre (mapa + tablas) sin código por objeto. Core (`../sapgis`) no tiene seed, export ni
import masivo: todo se crea por REST. El usuario decidió:

- **Solo el modelo**: spec de objetos/campos/relaciones (JSON) + script que la aplica a Core. Los
  datos los carga él con otra herramienta (el README documenta el mapeo capa→objeto y el orden).
- ENUM guarda la **etiqueta** del dominio (no el código).
- `PADRON_PREDIOS` es objeto aparte con relación opcional a `predio` (cruza por dirección 3716/3749).
- Omitir `CF_LOTES_PUN_18`; SRID de almacenamiento **32718**.
- Entregable en `sapgis/examples/perene/`. **Sin commits** (otra sesión usa el worktree de gisxp).

## Análisis del GDB (hechos verificados con ogrinfo/GDAL)

| Capa | Geom | Filas | Notas |
|---|---|---|---|
| CF_SECTOR_18 | MultiPolygon | 12 | COD_SECT 01‑12 |
| CF_UNIDADES_URBANAS_18 | MultiPolygon | 18 | TIPO_UU solo 01/26 usados; CONDIC todo 1 |
| CF_MANZANA_CAT_18 | MultiPolygon (hasta 3 partes) | 323 | única por COD_SECT+COD_MZN |
| CF_MANZANA_URB_18 | MultiPolygon (3 partes) | 341 | COD_UU + MZN_URB |
| CF_LOTES_POL_18 | MultiPolygon | 3 977 | ID_LOTE_P único; 17/40 columnas 100 % vacías |
| CF_LOTES_PUN_18 | Point | 5 223 | omitido (derivado) |
| CF_PREDIO_18 | Point | 3 977 | COD_PRE único; 1:1 con lote (ID_LOTE_P); 1 punto outlier al sur |
| CF_EJE_VIAL_18 | MultiLine (3 partes) | 228 | ID_VIA |
| CF_SEG_VIAL_18 | MultiLine | 644 | ID_SVIA; ID_VIA → eje 644/644 |
| CF_ARANCEL_18 | MultiLine (3 partes) | 1 374 | ID_ARANC; ID_SVIA → seg 1265/1374 (7 % sin vía) |
| CAL_VL_ARANCEL_18 | MultiLine | 1 374 | misma clave; columnas por año 2018‑2026 → se despliega en `valoracion` |
| CF_PARQUES_18 | MultiPolygon | 16 | |
| CF_EQUIPAMIENTO_18 | Point | 58 | NOM_EQUIP 91 % vacío |
| TB_VALORACION_HIST | tabla | 12 366 | ID_ARANC × año 2018‑2026, todos cruzan |
| PADRON_PREDIOS | tabla | 3 749 | COD_PRE/COD_CAT/autoavalúo 100 % vacíos; LLAVE = DIR_URB del predio |
| fras_* | tablas raster | 0 | ignoradas |

Uniones verificadas: predio→lote 3977/3977 · lote→manzana_cat 3977 · lote→arancel 3977 ·
valoración→arancel 12366 · seg→eje 644 · arancel→seg 1265/1374 · padrón→predio (LLAVE=DIR_URB) 3716/3749.

Dominios (17 usados): ESTADO_ACT, TIPO_UU(+TIPO_UU_2), ABREV_UU, CONDICION, TIP_VIA, ABRE_TIPVIA,
TIP_EDIFICACION, TIP_INTERIOR, LADO, TIPO_LOTE(+_1), DIS_PAR, COLINDANCIA, MATERIAL_VIA, SERVICIO,
RANGO_VIA, TIPO_EQUIP, TIPO_PREDIO, TIPO_DIREC, TIP_VINC. Solo se modelan los que tienen datos.

## Restricciones de Core que condicionan el diseño

(`sapgis/backend/src/main/kotlin/com/sapgis/…`)
- Tipos: TEXT, LONG_TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM, EMAIL, URL, UUID,
  RELATION, GEOMETRY (`metadata/FieldType.kt`). GEOMETRY: POINT/LINESTRING/POLYGON/MULTI*, `srid`,
  `dimension` 2|3; no promueve Polygon→MultiPolygon (`data/PhysicalTableRecordStore.kt`).
- Nombres `^[a-z][a-z0-9_]{0,48}$`; objeto ≤39, campo ≤49, relación ≤35; ~80 keywords SQL
  prohibidos; reservados `id, organization_id, created_at, updated_at, created_by, updated_by,
  workflow_state, version`; evitar `page,size,sort,dir,q,bbox,geometry,limit` (`platform/SqlIdentifier.kt`,
  `data/RecordQueryParams.kt`).
- ENUM = lista plana de strings, cada opción `^[\p{L}0-9 _.-]{1,64}$` (sin `/`, `'`, `(`).
- `POST /api/objects` crea objeto + campos + tabla (`metadata/MetadataDtos.kt` `CreateObjectRequest`).
  `POST /api/relationships {name,label,inverseLabel,type,source,target,fieldName}` MANY_TO_ONE crea
  el campo RELATION en `source` (409 si ya existe) (`metadata/RelationshipService.kt`).
- Auth: `POST /api/auth/login {email,password}` → `{token}`; `Authorization: Bearer`. Dev:
  `admin@sapgis.local` / `admin` (`db/migration/V2__seed_dev.sql`).
- `DELETE /api/objects/{o}` es 409 si otro objeto tiene RELATION hacia él → borrar en orden inverso.

## Spec del modelo

Convenciones: nombres técnicos ASCII minúscula; `label`/`pluralLabel` en español; cada objeto lleva
`id_gdb` INTEGER unique (OBJECTID/ID_* de origen) para que el cargador resuelva relaciones; se
descartan columnas 100 % vacías, `Shape_*`, `COORD_X/Y`, `ZONA_UTM`, `UBIGEO`, `SEC_EJEC`, `ESTADO_INS`,
`ABR_TUU`, `ABR_TVIA`, `DES_VIA`, `id_lote_sirv`; `ANO_*` → INTEGER; geometrías SRID 32718 dim 2.
Los objetos se crean **sin** campos RELATION; las relaciones los añaden.

### ENUMs (definidos una vez en `model.json`, referenciados por nombre)

- `fuente`: CARTOGRAFIA COFOPRI, CARTOGRAFIA MUNICIPAL, REPORTE COFOPRI, LISTADO ARANCELARIO, PLANO ARANCELARIO
- `tipo_unidad_urbana`: las 45 etiquetas de TIPO_UU_2 (superconjunto), con `PROGRAM MUNICIP'AL DE VIVIENDA` → `PROGRAMA MUNICIPAL DE VIVIENDA`
- `tipo_via`: AVENIDA, CALLE, JIRON, PASAJE, ALAMEDA, CARRETERA, PROLONGACION, PASEO, MALECON, CAMINO, PLAZA, PLAZUELA
- `condicion`: Formal, Informal · `estado`: ACTIVO, INACTIVO · `tipo_lote`: Regular, Mediterraneo, Uso Comun
- `tipo_predio`: REGULAR, MATRIZ, AIRES · `vinculacion`: NO VALIDADO, VALIDADO
- `colindancia`: Predio Urbano, Predio Rustico o Agricola, Quebrada, Canales o Similares, Zona de Riesgo, Rio, Limite Distrital
- `material_via`: DE TIERRA, DE AFIRMADO, DE EMPEDRADO, DE ASFALTO, DE CONCRETO
- `servicios`: los 8 de SERVICIO (`ADL-CON AGUA CON DESAGUE CON LUZ` … `SN-SIN AGUA SIN DESAGUE SIN LUZ`)
- `tipo_equipamiento`: EDUCATIVO, SALUD, RECREATIVO O DEPORTIVO (sin `/`), CULTURAL, COMERCIAL, ADMINISTRATIVO, SEGURIDAD, USOS ESPECIALES
- `grupo_uso`: VIVIENDA, COMERCIO, OTROS FINES, SERVICIOS COMUNALES, USO DEPORTES, AREA DESTINADA A EDUCACION
- `estado_via`: Definido, Por definir · `tipo_documento`: DNI, OT

### Objetos (orden de creación = orden topológico)

1. `sector` (Sector/Sectores) MULTIPOLYGON `geom`: `id_gdb`, `codigo` TEXT req uniq (COD_SECT), `fuente` ENUM.
2. `unidad_urbana` (Unidad urbana/Unidades urbanas) MULTIPOLYGON: `id_gdb`, `codigo` req uniq (COD_UU), `nombre` req (NOM_UU), `tipo` ENUM tipo_unidad_urbana (TIPO_UU), `partida`, `tipo_resolucion` (TIP_RES), `resolucion` (RESOLU), `condicion` ENUM, `fuente`.
3. `via` (Vía/Vías) MULTILINESTRING: `id_gdb` (ID_VIA), `codigo` req uniq (COD_VIA), `tipo` ENUM tipo_via (TIP_VIA), `nombre` req (NOM_VIA), `cantidad_segmentos` INTEGER (CANT_SEG), `condicion` ENUM, `fuente`.
4. `manzana_catastral` (Manzana catastral/Manzanas catastrales) MULTIPOLYGON: `id_gdb` (ID_MZN_C), `cod_sect`, `cod_mzn` req, `anio_cartografia` INTEGER (ANO_CART), `fuente`. Rel → sector.
5. `manzana_urbana` (Manzana urbana/Manzanas urbanas) MULTIPOLYGON: `id_gdb` (ID_MZN_U), `cod_sect`, `cod_mzn`, `mzn_urb` req, `cod_uu`, `anio_cartografia`, `fuente`. Rel → sector, unidad_urbana.
6. `segmento_via` (Segmento vial/Segmentos viales) MULTILINESTRING: `id_gdb` (ID_SVIA), `cod_via`, `cod_sect`, `cod_segmento` req (COD_SEGM), `tipo_via` ENUM, `nombre_via`, `fuente`. Rel → via, sector.
7. `frente_arancelario` (Frente arancelario/Frentes arancelarios) ← CF_ARANCEL_18, MULTILINESTRING: `id_gdb` (ID_ARANC), `cod_uu`, `cod_sect`, `cod_mzn`, `frente_manzana` (F_MZN), `mzn_urb`, `cod_via`, `tipo_via` ENUM, `nombre_via`, `anio_ejecucion` INTEGER (ANO_EJEC), `valor_arancel` DECIMAL (VAL_ACT), `tipo_resolucion`, `resolucion`, `condicion` ENUM, `colindancia` ENUM (COLING), `fuente`. Rel → segmento_via, manzana_catastral, unidad_urbana.
8. `lote` (Lote/Lotes) MULTIPOLYGON: `id_gdb` (ID_LOTE_P), `cod_sect`, `cod_mzn`, `cod_lote` req, `cod_uu`, `cod_via`, `mzn_urb`, `lot_urb`, `tipo_via` ENUM, `nombre_via`, `partida`, `tipo_lote` ENUM (TIP_LOT), `anio_cartografia`, `fuente`. Rel → manzana_catastral, unidad_urbana, frente_arancelario.
9. `predio` (Predio/Predios) POINT: `id_gdb` (ID_PRED), `codigo` req uniq (COD_PRE), `cod_sect`, `cod_mzn`, `cod_lote`, `cod_uu`, `mzn_urb`, `lot_urb`, `tipo_via` ENUM, `nombre_via`, `direccion` (DIR_URB), `direccion_completa` (DIREC_COM), `partida`, `anio_cartografia`, `fuente`, `estado` ENUM (ESTADO), `tipo_predio` ENUM (TIPO_PRED), `vinculacion` ENUM (OBS_VINC), `valor_arancel` DECIMAL (VAL_ACT), `area_terreno` DECIMAL (Area_terreno). Rel → lote, manzana_catastral, frente_arancelario.
10. `valoracion` (Valoración arancelaria/Valoraciones arancelarias), sin geometría ← TB_VALORACION_HIST (+ CAL_VL_ARANCEL_18 desplegado): `anio` INTEGER req (ANO_EJEC), `valor_arancel` DECIMAL (VAL_ACT / ARANCEL_yyyy), `material_via` ENUM (MATERIAL_yyyy), `servicios` ENUM (SERVICIOS_yyyy), `fuente`. Rel → frente_arancelario **required**.
11. `parque` (Parque/Parques) MULTIPOLYGON: `id_gdb` (ID_PARQ), `cod_sect`, `cod_mzn`, `mzn_urb`, `tipo` TEXT (TIP_PARQ), `nombre` req (NOM_PARQ), `fuente`. Rel → sector.
12. `equipamiento` (Equipamiento/Equipamientos) POINT: `id_gdb` (ID_EQUIP), `tipo` ENUM tipo_equipamiento (TIP_EQUIP), `clase` (CLAS_EQUIP), `nombre` (NOM_EQUIP), `fuente`.
13. `padron` (Padrón/Padrón de predios), sin geometría ← PADRON_PREDIOS: `id_gdb` (OBJECTID), `tipo_uu` ENUM tipo_unidad_urbana (ya viene como etiqueta), `nombre_uu`, `mzn_urb`, `lot_urb`, `tipo_via` ENUM, `nombre_via`, `direccion` (LLAVE), `direccion_completa` (llave_2), `grupo_uso` ENUM (Grupo_uso_desc), `area_terreno` DECIMAL (Area_Terreno), `tipo_documento` ENUM (TIP_DOC, trim), `documento` (DOC_IDEN), `nombres` (NOMBRE), `apellido_paterno` (AP_PAT), `apellido_materno` (AP_MAT), `partida`, `lote_mediterraneo` BOOLEAN (LOTE_MEDITERRANEO 0/1), `estado_via` ENUM (ESTADO_VIA). Rel → predio (opcional; el cargador cruza `LLAVE` = `predio.direccion`).

Campo geometría se llama `geom` en todos los espaciales (label "Geometría"), `required: false`.

### Relaciones (17, todas MANY_TO_ONE, `name` = `<source>_<target>` ≤35, `fieldName` = target)
excepción: `frente_aranc_manzana_catastral` (el nombre completo tiene 36 > 35 caracteres).

manzana_catastral→sector · manzana_urbana→sector · manzana_urbana→unidad_urbana · segmento_via→via ·
segmento_via→sector · frente_arancelario→segmento_via · frente_arancelario→manzana_catastral ·
frente_arancelario→unidad_urbana · lote→manzana_catastral · lote→unidad_urbana ·
lote→frente_arancelario · predio→lote · predio→manzana_catastral · predio→frente_arancelario ·
valoracion→frente_arancelario · parque→sector · padron→predio.
(= 17; `inverseLabel` en plural español, p. ej. "Lotes", "Predios", "Valoraciones".)

## Entregable: `sapgis/examples/perene/`

```
examples/perene/
  README.md        origen, decisiones, tabla capa→objeto→campo, orden de carga, ogr2ogr a GeoJSON 4326
  model.json       { "srid": 32718, "enums": {…}, "objects": [ {name,label,pluralLabel,description,
                     fields:[{name,label,type,required?,unique?,enum?,geometryType?,source?}]} ],
                     "relationships": [ {name,label,inverseLabel,source,target,fieldName} ] }
  apply.py         stdlib only (urllib, json, argparse). CLI: --core http://localhost:8090
                   --email admin@sapgis.local --password admin [--model model.json] [--drop] [--dry-run]
  test_apply.py    unittest: fake HTTP server (http.server en hilo) que graba las peticiones
  test_model.py    unittest: valida model.json contra las reglas de Core (regex nombres, longitudes,
                   keywords, opciones ENUM, targets existentes, orden topológico, sin RELATION en fields)
```

`apply.py`: (1) login; (2) `GET /api/objects` para saber qué existe; (3) por cada objeto en orden:
expande `enum` → `enumOptions`, añade `geometryType/srid/dimension` a la geometría, `POST /api/objects`,
salta si ya existe; (4) por cada relación `POST /api/relationships`, salta 409; (5) `--drop` borra
relaciones y luego objetos en orden inverso; (6) cualquier otro error: imprime status + cuerpo y sale
con 1. `--dry-run` imprime los payloads sin llamar.

No se toca `gisxp`. No se hace commit en ningún repo.

