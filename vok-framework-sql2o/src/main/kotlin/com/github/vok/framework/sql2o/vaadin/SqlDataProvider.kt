package com.github.vok.framework.sql2o.vaadin

import com.github.vokorm.Filter
import com.github.vokorm.db
import com.vaadin.data.provider.AbstractBackEndDataProvider
import com.vaadin.data.provider.Query
import com.vaadin.data.provider.QuerySortOrder
import com.vaadin.shared.data.sort.SortDirection
import java.util.stream.Stream

/**
 * Allows the coder to write any SQL he wishes. This provider must be simple enough to not to get in the way by smart (complex) Kotlin language features.
 * It should support any SQL select, but should allow for adding custom filters and orderings (since this is plugged into Grid after all).
 *
 * The provider is bound to a *holder class* which holds the values (any POJO). Sql2o is used to map the result set to the class. For example:
 *
 * ```
 * data class CustomerAddress(val customerName: String, val address: String)
 *
 * val provider = SqlDataProvider(CustomerAddress::class.java, """select c.name as customerName, a.street || ' ' || a.city as address
 *     from Customer c inner join Address a on c.address_id=a.id where 1=1 {{WHERE}} order by 1=1{{ORDER}} {{PAGING}}""", idMapper = { it })
 * ```
 *
 * (Note how select column names must correspond to field names in the `CustomerAddress` class)
 *
 * Now SqlDataProvider can hot-patch the `where` clause computed from Grid's filters into `{{WHERE}}` (as a simple string replacement),
 * and the `order by` and `offset`/`limit` into the `{{ORDER}}` and `{{PAGING}}`, as follows:
 *
 * * `{{WHERE}}` will be replaced by something like this: `"and name=:pqw5as and age>:p123a"` - note the auto-generated parameter
 *   names starting with `p`. If there are no filters, will be replaced by an empty string.
 * * `{{ORDER}}` will be replaced by `", customerName ASC, street ASC"` or by an empty string if there is no ordering requirement.
 * * `{{PAGING}}` will be replaced by `"offset 0 limit 100"` or by an empty string if there are no limitations.
 *
 * Note that the Grid will display fields present in the `CustomerAddress` holder class and will also auto-generate filters
 * for them, based on the type of the field.
 *
 * No bloody annotations! Work in progress. It is expected that a holder class is written for every select, tailored to show the outcome
 * of that particular select.
 *
 * @param clazz the type of the holder class which will hold the result
 * @param sql the select which can map into the holder class (that is, it selects columns whose names match the holder class fields). It should contain
 * `{{WHERE}}`, `{{ORDER}}` and `{{PAGING}}` strings which will be replaced by a simple substring replacement.
 * @param params the [sql] may be parametrized; this map holds all the parameters present in the sql itself.
 * @param idMapper returns the primary key which must be unique for every row returned. If the holder class is a data class and/or has proper equals/hashcode, the class itself may act as the key; in such case
 * just pass in identity here: `{ it }`
 * @param T the type of the holder class.
 * @author mavi
 */
class SqlDataProvider<T: Any>(val clazz: Class<T>, val sql: String, val params: Map<String, Any?> = mapOf(), val idMapper: (T)->Any) : AbstractBackEndDataProvider<T, Filter<T>?>() {
    override fun getId(item: T): Any = idMapper(item)
    override fun toString() = "SqlDataProvider($clazz:$sql($params))"

    override fun sizeInBackEnd(query: Query<T, Filter<T>?>?): Int = db {
        val q: org.sql2o.Query = con.createQuery(query.computeSQL(true))
        params.entries.forEach { (name, value) -> q.addParameter(name, value) }
        q.fillInParamsFromFilters(query)
        val count: Int = q.executeScalar(Int::class.java) ?: 0
        count
    }

    override fun fetchFromBackEnd(query: Query<T, Filter<T>?>?): Stream<T> = db {
        val q = con.createQuery(query.computeSQL(false))
        params.entries.forEach { (name, value) -> q.addParameter(name, value) }
        q.fillInParamsFromFilters(query)
        val list = q.executeAndFetch(clazz)
        list.stream()
    }

    private fun org.sql2o.Query.fillInParamsFromFilters(query: Query<T, Filter<T>?>?): org.sql2o.Query {
        val filters: Filter<T> = query?.filter?.orElse(null) ?: return this
        val params: Map<String, Any?> = filters.getSQL92Parameters()
        params.entries.forEach { (name, value) ->
            require(!this@SqlDataProvider.params.containsKey(name)) { "Filters tries to set the parameter $name to $value but that parameter is already forced by SqlDataProvider to ${params[name]}: filter=$filters dp=${this@SqlDataProvider}" }
            addParameter(name, value)
        }
        return this
    }

    /**
     * Using [sql] as a template, computes the replacement strings for the `{{WHERE}}`, `{{ORDER}}` and `{{PAGING}}` replacement strings.
     */
    private fun Query<T, Filter<T>?>?.computeSQL(isCountQuery: Boolean): String {
        // compute the {{WHERE}} replacement
        var where: String = this?.filter?.orElse(null)?.toSQL92() ?: ""
        if (where.isNotBlank()) where = "and $where"

        // compute the {{ORDER}} replacement
        var orderBy: String = if (isCountQuery) "" else this?.sortOrders?.toSql92OrderByClause() ?: ""
        if (orderBy.isNotBlank()) orderBy = ", $orderBy"

        // compute the {{PAGING}} replacement
        val offset: Int? = this?.offset
        val limit: Int? = this?.limit.takeUnless { it == Int.MAX_VALUE }
        // MariaDB requires LIMIT first, then OFFSET: https://mariadb.com/kb/en/library/limit/
        val paging: String = if (!isCountQuery && offset != null && limit != null) " LIMIT $limit OFFSET $offset" else ""

        var s: String = sql.replace("{{WHERE}}", where).replace("{{ORDER}}", orderBy).replace("{{PAGING}}", paging)

        // the count was obtained by a dirty trick - the ResultSet was simply scrolled to the last line and the row number is obtained.
        // however, PostgreSQL doesn't seem to like this: https://github.com/mvysny/vaadin-on-kotlin/issues/19
        // anyway there is a better way: simply wrap the select with "SELECT count(*) FROM (select)"
        if (isCountQuery) {
            // subquery in FROM must have an alias
            s = "SELECT count(*) FROM ($s) AS Foo"
        }
        return s
    }

    /**
     * Converts Vaadin [QuerySortOrder] to something like "name ASC".
     */
    private fun QuerySortOrder.toSql92OrderByClause(): String =
        "$sorted ${if (direction == SortDirection.ASCENDING) "ASC" else "DESC"}"

    /**
     * Converts a list of Vaadin [QuerySortOrder] to something like "name DESC, age ASC". If the list is empty, returns an empty string.
     */
    private fun List<QuerySortOrder>.toSql92OrderByClause(): String =
        joinToString { it.toSql92OrderByClause() }
}
