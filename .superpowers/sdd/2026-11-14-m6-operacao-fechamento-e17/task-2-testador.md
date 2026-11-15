# Task 2 (E17) - relatório do testador

Status: RED (1 defeito de teste existente, 1 teste vermelho novo).

## Mutação (feita e desfeita)
Copiei `api/openapi.yaml` para o scratchpad e troquei o texto novo de `GuestResponse.genderDescription` por
`...inclusive nas\` / `\ das escritas (`GuestWriteSummaryResponse`, `guests.profiles.read`).` (frase antiga partida
numa quebra, com as duas asserções positivas satisfeitas). Rodei `./gradlew test --tests '*GuestGenderDescriptionApiTest.the contract exposes*'`:
`BUILD SUCCESSFUL`, XML `tests="1" skipped="0" failures="0"`. A asserção negativa NÃO pegou o texto antigo.
Causa: a normalização `Regex("\\\\\\n\\s*(\\\\ )?")` remove também o `\ ` da linha seguinte, que no YAML é um espaço;
o resultado é "inclusive nasdas escritas". Só pega a frase quando a quebra cai fora de um espaço. Contrato restaurado
(`git status` limpo).
Não alterei a asserção existente (regra); aponto: deveria trocar `\ ` por espaço (`.replace("\\ ", " ")`) depois de remover `\<quebra>` e o recuo.

## Teste novo (arquivo próprio): GuestGenderDescriptionContractTextTest
- `the existing normalization keeps the space when the old phrase breaks at a space`: VERMELHO. Falha em
  `GuestGenderDescriptionContractTextTest.kt:31` (AssertionFailedError; `2 tests completed, 1 failed`). Esperado: a
  normalização devolver "inclusive nas das escritas" para a frase quebrada num espaço; obtido "nasdas".
- `the contract description no longer promises ... whatever the line breaks`: VERDE; decodifica o YAML corretamente e
  confirma que o contrato real não tem a frase antiga e cita `GuestWriteSummaryResponse`.

## Textos vs código da Task 1
Contrato, README, KDocs (`GuestsPorts.kt`, `GuestsController.kt`): consistentes. `writeResult` (GuestsSupport.kt:150)
só inclui o perfil com `guests.profiles.read`, usado por restrict, lift-restriction (GuestService) e merge
(GuestMergeService:149); create/update seguem devolvendo o perfil inteiro, como o texto diz. Nada prometido a mais.

## Suíte
Rodei só focados (um Gradle por vez, exceto um lint concorrente por engano com o focado, sem efeito) e
`./gradlew ktlintCheck detektTest`: BUILD SUCCESSFUL. Suíte inteira não rodada (pedido: focados + lint).
