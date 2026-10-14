package com.zeny.hotel.shared.infrastructure.audit

import com.zeny.hotel.shared.AuditTrail
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DomainException
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock

/**
 * Aspecto de `@Audited` (ARCHITECTURE §5, passo 6): roda **dentro** da transação do caso de uso
 * (ordem abaixo da do `@Transactional`, [AuditOrdering]), abre o escopo da [AuditScopes], executa
 * o método e, ao fim, entrega as entradas à [AuditTrail], que as grava na mesma transação. Se o
 * método lança, nada é gravado, salvo as recusas de `recordRejections`, que também só ficam se a
 * transação confirmar. Uma falha ao gravar a entrada propaga e desfaz a escrita.
 *
 * O ator é o atribuído pelo caso de uso ou o da requisição ([CurrentActor]); sem nenhum, a
 * entrada é do sistema. As dependências vêm por [ObjectProvider] para o aspecto não antecipar a
 * criação dos beans de `iam` e `audit` (que ele mesmo intercepta).
 */
@Aspect
@Component
@Order(AuditOrdering.AUDIT)
class AuditAspect(
    private val scopes: AuditScopes,
    private val trail: ObjectProvider<AuditTrail>,
    private val currentActor: ObjectProvider<CurrentActor>,
    private val clock: ObjectProvider<Clock>,
) {
    @Around("@annotation(com.zeny.hotel.shared.Audited)")
    fun audit(joinPoint: ProceedingJoinPoint): Any? {
        val method = (joinPoint.signature as MethodSignature).method
        val audited =
            checkNotNull(AnnotationUtils.findAnnotation(method, Audited::class.java)) {
                "@Audited não encontrada em $method"
            }
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) {
            "@Audited(${audited.action}) em ${method.declaringClass.simpleName}.${method.name} precisa rodar " +
                "numa transação de escrita (@Transactional sem readOnly): a entrada vai na mesma transação"
        }
        val scope = scopes.open(audited.action)
        try {
            val result = joinPoint.proceed()
            trail.getObject().append(scope.succeeded(actor(), clock.getObject().instant()))
            return result
        } catch (e: DomainException) {
            if (audited.recordRejections) {
                val records = scope.rejected(e.code.value, actor(), clock.getObject().instant())
                if (records.isNotEmpty()) trail.getObject().append(records)
            }
            throw e
        } finally {
            scopes.close(scope)
        }
    }

    private fun actor() = currentActor.getObject().currentOrNull()?.let { it.userId to it.deviceSessionId }
}

/**
 * Ordem dos interceptadores: a transação por fora e a auditoria por dentro, para a entrada ir na
 * mesma transação do caso de uso. Sem ordem explícita as duas teriam a mesma precedência (a mais
 * baixa) e a ordem entre elas seria indefinida.
 */
object AuditOrdering {
    const val TRANSACTION = Ordered.LOWEST_PRECEDENCE - 100
    const val AUDIT = Ordered.LOWEST_PRECEDENCE
}

/**
 * Liga o `@Transactional` (no lugar da configuração automática do Spring Boot, que recua) com a
 * ordem de [AuditOrdering.TRANSACTION] e proxies de classe, como o padrão do Boot.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true, order = AuditOrdering.TRANSACTION)
class TransactionOrderingConfiguration
