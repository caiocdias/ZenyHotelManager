package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.shared.Cpf
import java.util.UUID

/**
 * Qual CPF vai como solicitante à FNRH (D-E07-7, H16): o do usuário que fez a operação; sem ele, o padrão da
 * configuração.
 */
object RequesterCpfPolicy {
    /**
     * O CPF de [performedBy], se houver e ele tiver um cadastrado; senão [defaultCpf]; `null` se não há nenhum dos
     * dois. Sem [performedBy], não consulta [cpfOfUser].
     */
    fun choose(
        performedBy: UUID?,
        cpfOfUser: (UUID) -> Cpf?,
        defaultCpf: Cpf?,
    ): Cpf? = performedBy?.let(cpfOfUser) ?: defaultCpf
}
