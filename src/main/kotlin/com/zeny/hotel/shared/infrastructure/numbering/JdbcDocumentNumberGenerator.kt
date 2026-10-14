package com.zeny.hotel.shared.infrastructure.numbering

import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.DocumentNumberGenerator
import com.zeny.hotel.shared.DocumentNumberType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * Números legíveis pela sequence do tipo de documento (`nextval`). O nome da sequence vai
 * como parâmetro (`regclass`), nunca concatenado ao SQL.
 */
@Component
class JdbcDocumentNumberGenerator(
    private val jdbc: JdbcClient,
) : DocumentNumberGenerator {
    override fun next(
        type: DocumentNumberType,
        year: Int,
    ): DocumentNumber {
        val sequence =
            jdbc
                .sql("SELECT nextval(CAST(:sequence AS regclass))")
                .param("sequence", type.sequence)
                .query(Long::class.java)
                .single()
        return DocumentNumber(type.prefix, year, sequence)
    }
}
