package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.FnrhErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

/**
 * A configuração da integração com a FNRH (SPEC §8.2.17, D-E07-1): ambiente, usuário, senha, CPF solicitante padrão,
 * ligado e versão. Existe uma linha só, a semente da migração ([SINGLETON_ID]), para o primeiro `PUT` ter `ETag`.
 * Usuário, senha e CPF ficam cifrados no banco (`EncryptedStringConverter`). Mudam por [update] ou, para apagar a
 * credencial, por [clearCredentials] (M5 E16).
 *
 * **Nunca** interpole o [defaultCpf] em log, mensagem ou `toString`: `Cpf.toString()` devolve os dígitos.
 */
@Entity
@Table(name = "settings", schema = "fnrh")
class FnrhSettings private constructor(
    @Id
    val id: UUID,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var environment: FnrhEnvironment = FnrhEnvironment.HOMOLOGATION
        protected set

    @Convert(converter = EncryptedStringConverter::class)
    @Column
    var username: String? = null
        protected set

    @Convert(converter = EncryptedStringConverter::class)
    @Column
    var password: String? = null
        protected set

    /** Os dígitos do CPF, cifrados. */
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "default_cpf")
    private var encodedDefaultCpf: String? = null

    @Column(nullable = false)
    var enabled: Boolean = false
        protected set

    @Version
    var version: Long? = null
        protected set

    /** O CPF solicitante padrão da integração. */
    val defaultCpf: Cpf? get() = encodedDefaultCpf?.let(Cpf::of)

    /**
     * Troca a configuração. [password] nula mantém a atual; trocar de [environment] exige uma [password] nova; ligar
     * ([enabled]) exige usuário, senha e CPF. Nada muda se uma regra falha.
     */
    fun update(
        environment: FnrhEnvironment,
        username: String?,
        password: String?,
        defaultCpf: Cpf?,
        enabled: Boolean,
    ) {
        if (environment != this.environment && password == null) {
            throw BusinessRuleException(
                FnrhErrorCodes.FNRH_PASSWORD_REQUIRED_FOR_ENVIRONMENT_CHANGE,
                "Para trocar de ambiente, informe a senha de novo.",
            )
        }
        val newUsername = username?.trim()?.ifEmpty { null }
        requireValidUsername(newUsername)
        val newPassword = password ?: this.password
        val complete = newUsername != null && !newPassword.isNullOrEmpty() && defaultCpf != null
        if (enabled && !complete) {
            throw BusinessRuleException(
                FnrhErrorCodes.FNRH_SETTINGS_INCOMPLETE,
                "Para ligar a integração, informe o usuário, a senha e o CPF solicitante padrão.",
            )
        }
        this.environment = environment
        this.username = newUsername
        this.password = newPassword
        this.encodedDefaultCpf = defaultCpf?.digits
        this.enabled = enabled
    }

    /**
     * Apaga a credencial: usuário, senha e CPF solicitante padrão viram nulos e a integração desliga. O [environment]
     * não muda. Ligar de novo exige informar a credencial inteira (M5 E16, D-E16-2).
     */
    fun clearCredentials() {
        username = null
        password = null
        encodedDefaultCpf = null
        enabled = false
    }

    private fun requireValidUsername(username: String?) {
        if (username != null && ':' in username) {
            throw BusinessRuleException(
                FnrhErrorCodes.FNRH_USERNAME_INVALID,
                "O usuário da FNRH não pode conter dois-pontos.",
            )
        }
    }

    override fun toString(): String = "FnrhSettings(environment=$environment, enabled=$enabled)"

    companion object {
        /** A chave da única linha da tabela, semeada pela migração. */
        val SINGLETON_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000f001")

        /** A configuração inicial: desligada, em homologação e vazia. */
        fun initial(): FnrhSettings = FnrhSettings(SINGLETON_ID)
    }
}
