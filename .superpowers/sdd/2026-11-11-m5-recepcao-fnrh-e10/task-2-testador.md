# Task 2 (M5 E10): relatório do testador

Status: vermelha com 6 testes de defeito conhecido (validação de nome e endereço). Nenhum código de produção tocado.

## Cobertura nova (37 testes unitários + 5 de banco)
Arquivos: `src/test/kotlin/com/zeny/hotel/printing/PrintAgentAttackTest.kt` e `PrintingEntitiesAttackTest.kt`.
- Token: 32 bytes decodificados, sem preenchimento, reencode idêntico, 10 mil tokens e hashes distintos, bits balanceados
  (4000 amostras), vetores SHA-256 conhecidos, hash do agente = hash do token, token fora de toString e da exceção.
- Rotação: stamp sobe com relógio parado, todos os anteriores invalidados, inativo não muda hash nem geração,
  desativar duas vezes, touch/rename não mexem na credencial.
- touch: 60 s exatos não muda, 60 s + 1 ns muda, relógio para trás, janela não adianta.
- Nome/endereço: vazio, só espaço (vários tipos), 100/101 e 255/256, controle C0/DEL/C1, U+2028/2029, formatação
  (U+200B, 200D, 200E, 202E, FEFF, 00AD), espaço comum/NBSP/U+3000 no endereço, update inválido sem efeito parcial.
- Enums e códigos de erro públicos (10 códigos, nomes iguais ao campo, mediaType, ordem do ciclo).
- Banco (ddl-auto validate): agente e impressora persistidos e relidos, rotação, touch, desativação (hash nulo no SQL),
  update da impressora, token em claro ausente da linha.

## Comando e saída
`./gradlew test --tests 'com.zeny.hotel.printing.*'` -> `92 tests completed, 6 failed` (todos em PrintAgentAttackTest;
PrintingEntitiesAttackTest 5/5 verde). `./gradlew ktlintFormat detekt detektTest` verde.

## Defeitos (testes vermelhos, commitados como estão)
Causa única: `requireValidName` e `requireValidAddress` (PrintAgent.kt, Printer.kt) usam só `Char.isISOControl()`.
Isso não cobre U+2028/U+2029 (categorias Zl/Zp) nem caracteres de formatação (Cf: U+200B, U+200E, U+202E, U+FEFF...).
Esperado (D-E10-8: "sem controle"; o brief pede U+2028/2029 e formatação recusados): `IllegalArgumentException`.
Obtido: `AssertionError: Expecting code to raise a throwable` (aceito).
1. `nome com separador de linha ou paragrafo U+2028 e U+2029 e recusado`
2. `nome com caractere de formatacao invisivel e recusado`
3. `nome so de caracter invisivel nao vira nome valido` (nome só de U+200B passa, aparece vazio)
4. `endereco com caractere de formatacao invisivel e recusado` (a falta de U+2028 no endereço é coberta por isWhitespace)
5. `rename recusado nao altera o nome` (rename("a b") passa: nome vira "a b")
6. `nome da impressora segue as mesmas regras do agente`
Hipótese de correção: rejeitar `Character.getType` em CONTROL, FORMAT, LINE_SEPARATOR, PARAGRAPH_SEPARATOR (e, no nome,
exigir ao menos um caractere visível depois do aparar).

## Observações sem alteração
- Nome é aparado, então `"a\n"` vira `"a"` (aceito, por desenho); o teste de controle vale só no meio do nome.
- `touch` com o relógio muito atrás do último contato nunca grava até o relógio alcançar; aceitável, só documentado.
- Testes do desenvolvedor não foram alterados.

## Correção posterior
O U+FEFF literal se perdera na escrita (a lista tinha uma string vazia, e "Balc" + "" + "ao" é válido). Troquei todos os
invisíveis literais de PrintAgentAttackTest.kt por escapes \uXXXX. Produção corrigida pelo desenvolvedor em 8e62417.
