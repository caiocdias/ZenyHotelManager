package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * O CPF solicitante de um usuário (D-E07-7). O [userId] é opaco: o `fnrh` não importa `iam`. O CPF fica cifrado no
 * banco (`EncryptedStringConverter`).
 *
 * **Nunca** interpole o [cpf] em log, mensagem ou `toString`: `Cpf.toString()` devolve os dígitos.
 */
@Entity
@Table(name = "requester", schema = "fnrh")
class FnrhRequester(
    @Id
    @Column(name = "user_id")
    val userId: UUID,
    cpf: Cpf,
) {
    /** Os dígitos do CPF, cifrados. */
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "cpf", nullable = false)
    private var encodedCpf: String = cpf.digits

    val cpf: Cpf get() = Cpf.of(encodedCpf)

    fun replaceCpf(cpf: Cpf) {
        encodedCpf = cpf.digits
    }

    override fun toString(): String = "FnrhRequester(userId=$userId)"
}
