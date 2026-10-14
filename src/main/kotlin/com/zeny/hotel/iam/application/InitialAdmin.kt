package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.domain.Logins
import com.zeny.hotel.iam.domain.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Administrador inicial (H2): no boot, se não existe nenhum usuário, cria um a partir de
 * `ZENY_ADMIN_LOGIN` e `ZENY_ADMIN_PASSWORD`, com troca de senha obrigatória no primeiro
 * login. Se já existe usuário, não faz nada (as variáveis podem ficar vazias). Sem usuário e
 * sem as variáveis, a aplicação não sobe: ninguém conseguiria entrar.
 *
 * As permissões de administrador chegam com o catálogo de permissões (M1 E02).
 */
@Service
class InitialAdminService(
    private val users: Users,
    private val hasher: PasswordHasher,
    private val properties: IamProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Devolve `true` se criou o administrador. */
    @Transactional
    fun ensureInitialAdmin(): Boolean {
        val admin = properties.initialAdmin
        if (users.anyExists()) {
            if (admin.password.isNotEmpty()) {
                log.warn("Já existem usuários: ZENY_ADMIN_PASSWORD foi ignorada; remova-a do ambiente")
            }
            return false
        }
        val login = Logins.normalize(admin.login)
        check(login != null && admin.password.isNotEmpty()) {
            "Nenhum usuário cadastrado: defina ZENY_ADMIN_LOGIN (3 a 100 caracteres: letras, dígitos e . _ @ + -) " +
                "e ZENY_ADMIN_PASSWORD para criar o administrador inicial"
        }
        check(admin.password.length in PasswordRules.LENGTH) {
            "ZENY_ADMIN_PASSWORD precisa ter de ${PasswordRules.MIN_LENGTH} a ${PasswordRules.MAX_LENGTH} caracteres"
        }
        val user =
            User.create(
                name = INITIAL_ADMIN_NAME,
                login = login,
                passwordHash = hasher.hash(admin.password),
                passwordChangeRequired = true,
                now = clock.instant(),
            )
        users.add(user)
        log.info("Administrador inicial criado (usuário {}); a senha deve ser trocada no primeiro login", user.id)
        return true
    }

    private companion object {
        const val INITIAL_ADMIN_NAME = "Administrador"
    }
}

/** Regras de senha (ASVS 2.1): de 12 a 128 caracteres; o hash Argon2id não limita o conteúdo. */
object PasswordRules {
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 128
    val LENGTH = MIN_LENGTH..MAX_LENGTH
}
