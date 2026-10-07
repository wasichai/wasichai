package wasichai.core.platform

import org.springframework.r2dbc.core.DatabaseClient

// r2dbc refuses bind(name, null): a null must say what type it stands for. one helper for core and
// modules alike, so no repository forgets the type or keeps a copy of its own.

// the type is the value's static type, boxed: a null Boolean binds as java.lang.Boolean, not boolean
inline fun <reified T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?
): DatabaseClient.GenericExecuteSpec = bindNullable(name, value, T::class.javaObjectType)

// the type known only at run time: a field type's javaType, a module's attribute column
fun DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: Any?,
    type: Class<*>
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
