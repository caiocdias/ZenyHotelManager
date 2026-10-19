package com.zeny.hotel.shared

/**
 * Marca, num DTO de resposta, a propriedade de tipo `enum` cujo conjunto de valores vai crescer (convenção decidida
 * pelo usuário em 2026-10-19, ARCHITECTURE §7): no contrato, ela sai como `string` com a lista em `x-extensible-enum`
 * (gerada das constantes do `enum`), não como `enum`, então acrescentar um valor não é breaking change e o cliente
 * trata um valor desconhecido sem falhar. Use com `@field:ExtensibleEnum`; um `enum` de resposta já publicado sem a
 * marca só muda para ela com a decisão registrada (o cliente gerado deixa de ter o tipo fechado).
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ExtensibleEnum
