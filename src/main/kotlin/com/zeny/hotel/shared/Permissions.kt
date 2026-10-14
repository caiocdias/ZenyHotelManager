package com.zeny.hotel.shared

import java.math.BigDecimal

/**
 * Código de uma permissão do catálogo (RF-ACS-02, ARCHITECTURE §8.2): o nome do módulo dono e
 * depois a ação, em segmentos separados por ponto (`iam.users.manage`, `housekeeping.task.complete`).
 * Cada segmento começa por letra minúscula e segue com letras e dígitos. Um código publicado não
 * muda: as roles o referenciam.
 */
@JvmInline
value class PermissionCode(
    val value: String,
) {
    init {
        require(value.length <= MAX_LENGTH && PATTERN.matches(value)) {
            "Código de permissão fora do padrão <módulo>.<ação>: '$value'"
        }
    }

    /** O módulo dono (primeiro segmento). */
    val module: String get() = value.substringBefore('.')

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 100
        private val PATTERN = Regex("[a-z][a-z0-9]*(\\.[a-z][a-zA-Z0-9]*)+")
    }
}

/**
 * Escopo de uma concessão (RF-ACS-05). [breadth] ordena do mais restrito ao mais amplo: na união
 * das roles prevalece o mais amplo (RF-ACS-04). O modelo aceita escopos novos no futuro (como
 * um de setor), entre os dois.
 */
@Suppress("MagicNumber") // a ordem relativa é o que importa; os vãos deixam lugar a escopos novos
enum class PermissionScope(
    val breadth: Int,
) {
    /** Só os registros atribuídos ao usuário ou criados por ele. */
    OWN(10),

    /** Todos os registros. */
    ALL(100),
}

/** Unidade de um limite: diz como o valor é validado e mostrado. */
enum class LimitUnit {
    /** Valor em reais, com até 2 casas (como [Money]). */
    MONEY,

    /** Pontos percentuais, de 0 a 100, com até 4 casas (como [Percent]). */
    PERCENT,

    /** Quantidade inteira. */
    QUANTITY,
    ;

    /** Se [value] (não negativo) cabe nesta unidade. */
    fun accepts(value: BigDecimal): Boolean {
        val normalized = value.stripTrailingZeros()
        return value.signum() >= 0 &&
            when (this) {
                MONEY -> normalized.scale() <= Money.SCALE
                PERCENT -> normalized.scale() <= Percent.SCALE && value <= HUNDRED
                QUANTITY -> normalized.scale() <= 0
            }
    }

    private companion object {
        val HUNDRED = BigDecimal(100)
    }
}

/**
 * Valor de um limite numa concessão (RF-ACS-06): um teto ou sem teto. Na união das roles
 * prevalece o maior ([max]); [Unlimited] vence qualquer teto. Uma concessão sem valor para uma
 * chave declarada vale [NONE] (teto zero).
 */
sealed interface LimitValue {
    /** Se o limite admite [amount]. */
    fun allows(amount: BigDecimal): Boolean

    data object Unlimited : LimitValue {
        override fun allows(amount: BigDecimal): Boolean = true
    }

    /** Teto não negativo; a escala é normalizada (`10.00` e `10` são o mesmo teto). */
    class Max(
        value: BigDecimal,
    ) : LimitValue {
        val value: BigDecimal = value.stripTrailingZeros()

        init {
            require(value.signum() >= 0) { "Limite negativo: $value" }
        }

        override fun allows(amount: BigDecimal): Boolean = amount <= value

        override fun equals(other: Any?): Boolean = other is Max && value.compareTo(other.value) == 0

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Max(${value.toPlainString()})"
    }

    companion object {
        /** Teto zero: nada acima de zero é permitido sem aprovação. */
        val NONE: LimitValue = Max(BigDecimal.ZERO)

        /** O maior dos dois (RF-ACS-04). */
        fun max(
            a: LimitValue,
            b: LimitValue,
        ): LimitValue =
            when {
                a is Unlimited || b is Unlimited -> Unlimited
                else -> if ((a as Max).value >= (b as Max).value) a else b
            }
    }
}

/** Parâmetro de limite que uma permissão aceita (RF-ACS-02), como `maxPercent` de um desconto. */
data class LimitDefinition(
    val key: String,
    val unit: LimitUnit,
    /** Texto em pt-BR para a tela de roles. */
    val description: String,
) {
    init {
        require(key.length <= MAX_KEY_LENGTH && KEY_PATTERN.matches(key)) { "Chave de limite fora do padrão: '$key'" }
        require(description.isNotBlank()) { "Limite '$key' sem descrição" }
    }

    /** Se [value] é um valor válido para este limite. */
    fun accepts(value: LimitValue): Boolean = value !is LimitValue.Max || unit.accepts(value.value)

    companion object {
        const val MAX_KEY_LENGTH = 100
        private val KEY_PATTERN = Regex("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*")
    }
}

/**
 * Modelos de role pré-carregados (SPEC §3.1, RF-ACS-03). O administrador pode editar, duplicar
 * ou desativar as roles criadas a partir deles. O [ADMINISTRATOR] recebe sozinho todas as
 * permissões do catálogo; os outros recebem as que cada módulo sugere em
 * [PermissionDefinition.templates] (H5).
 */
enum class RoleTemplate(
    val displayName: String,
    val description: String,
) {
    ADMINISTRATOR("Administrador", "Usuários, roles, configurações e integrações; todas as permissões."),
    MANAGER("Gerente", "Toda a operação, aprovações, tarifas e relatórios."),
    RECEPTIONIST("Recepcionista", "Reservas, check-in e check-out, contas, caixa da recepção e recados."),
    HOUSEKEEPER("Governanta", "Distribuição, inspeção, status de UH, achados e perdidos e modelos de checklist."),
    ROOM_ATTENDANT("Camareira", "Executar tarefas e checklists e registrar ocorrências."),
    MAINTENANCE("Manutenção", "Ordens de serviço, preventivas e pedidos de OOO/OOS."),
    WAITER("Garçom", "Abrir comandas, lançar itens e enviar à produção."),
    FNB_CASHIER("Caixa A&B", "Fechar comandas, caixa do restaurante e NFC-e."),
    KITCHEN_BAR("Cozinha/Bar", "Fila de produção."),
    SALES_EVENTS("Comercial/Eventos", "Eventos, propostas, espaços e bloqueios de grupo."),
    STOREKEEPER("Almoxarife", "Estoque, compras, inventário, rouparia e lavanderia."),
    FINANCE("Financeiro", "Contas a receber, fechamento de caixas, fiscal e relatórios."),
}

/** Concessão sugerida a um modelo de role: o escopo e, para as chaves declaradas, os limites. */
data class TemplateGrant(
    val scope: PermissionScope,
    val limits: Map<String, LimitValue> = emptyMap(),
)

/**
 * Uma permissão do catálogo (RF-ACS-02): código, texto em pt-BR para a tela de roles, escopos
 * suportados, se é sensível (RF-ACS-08: exige aprovação de superior quando falta, M1 E05) e os
 * limites que aceita. [templates] sugere a concessão aos modelos de role da SPEC §3.1 (o
 * administrador recebe todas sem precisar constar aqui).
 */
data class PermissionDefinition(
    val code: PermissionCode,
    val description: String,
    val scopes: Set<PermissionScope>,
    val sensitive: Boolean = false,
    val limits: List<LimitDefinition> = emptyList(),
    val templates: Map<RoleTemplate, TemplateGrant> = emptyMap(),
) {
    init {
        require(description.isNotBlank()) { "Permissão $code sem descrição" }
        require(scopes.isNotEmpty()) { "Permissão $code sem escopo suportado" }
        require(limits.map { it.key }.toSet().size == limits.size) { "Permissão $code com chave de limite repetida" }
        require(RoleTemplate.ADMINISTRATOR !in templates) {
            "Permissão $code: o modelo Administrador recebe todas as permissões sem precisar ser listado"
        }
        templates.forEach { (template, grant) ->
            require(
                grant.scope in scopes,
            ) { "Permissão $code: escopo ${grant.scope} sugerido a $template não é suportado" }
            grant.limits.forEach { (key, value) ->
                val limit = limitOf(key)
                require(
                    limit != null && limit.accepts(value),
                ) { "Permissão $code: limite '$key' inválido em $template" }
            }
        }
    }

    /** O escopo mais amplo que a permissão suporta. */
    val broadestScope: PermissionScope get() = scopes.maxBy { it.breadth }

    fun limitOf(key: String): LimitDefinition? = limits.firstOrNull { it.key == key }
}

/**
 * As permissões que um módulo declara (ARCHITECTURE §8.2). Cada módulo publica um bean desta
 * interface; o `iam` junta todos e sincroniza a tabela de permissões no boot (novas inseridas,
 * removidas marcadas como obsoletas, que deixam de conceder acesso). Todo código começa por
 * [module] e nenhum se repete entre módulos.
 */
interface ModulePermissions {
    /** Nome do módulo (pacote), prefixo de todos os códigos. */
    val module: String

    val permissions: List<PermissionDefinition>
}
