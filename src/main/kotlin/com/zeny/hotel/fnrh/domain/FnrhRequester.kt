package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

/**
 * O CPF solicitante de um usuário (D-E07-7).
 * Nada é apagado: desativar anula o CPF e mantém a linha, inativa. O [userId] é opaco: o `fnrh` não importa `iam`.
 * O CPF fica cifrado no banco (`EncryptedStringConverter`).
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
    @Column(name = "cpf")
    private var encodedCpf: String? = cpf.digits

    /** Se o usuário tem CPF solicitante vigente. Inativo: o CPF foi apagado (minimização de dado pessoal). */
    @Column(nullable = false)
    var active: Boolean = true
        protected set

    /** A versão: sobe a cada troca gravada, para a trilha registrar a troca por um CPF de mesmo final. */
    @Version
    var version: Long? = null
        protected set

    /** O CPF vigente, ou `null` se inativo. */
    val cpf: Cpf? get() = encodedCpf?.let(Cpf::of)

    /** Troca o CPF e, se estava inativo, reativa. */
    fun replaceCpf(cpf: Cpf) {
        encodedCpf = cpf.digits
        active = true
    }

    /** Apaga o CPF e marca como inativo; a linha fica. */
    fun deactivate() {
        encodedCpf = null
        active = false
    }

    override fun toString(): String = "FnrhRequester(userId=$userId)"
}
