package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.domain.FnrhSettings
import com.zeny.hotel.shared.Cpf
import java.net.URI

/**
 * Como se conectar à FNRH agora: a porta do adaptador do Serpro para a configuração gravada. Implementada em
 * `infrastructure`.
 */
fun interface FnrhConnectionSource {
    /** A conexão vigente, ou `null` com a integração desligada. */
    fun current(): FnrhConnection?
}

/**
 * Os dados de conexão. O `toString` mostra só o [environment]: usuário, senha e CPF nunca vão a log nem mensagem.
 */
data class FnrhConnection(
    val environment: FnrhEnvironment,
    val baseUrl: URI,
    val username: String,
    val password: String,
    val defaultRequesterCpf: Cpf,
) {
    override fun toString(): String = "FnrhConnection(environment=$environment)"
}

/** A linha única da configuração, na transação corrente. */
interface FnrhSettingsStore {
    /** A configuração (a linha semente sempre existe). */
    fun settings(): FnrhSettings

    /** Grava na hora as mudanças pendentes, para a versão (o `ETag`) já valer na resposta. */
    fun flush()
}
