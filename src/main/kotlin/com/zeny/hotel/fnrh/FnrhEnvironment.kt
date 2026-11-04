package com.zeny.hotel.fnrh

/** O ambiente da FNRH do Serpro a que o servidor se conecta (D-E07-1). Cada um tem a sua URL em `zeny.fnrh`. */
enum class FnrhEnvironment {
    /** Homologação (`hom-lowcode.serpro.gov.br`). */
    HOMOLOGATION,

    /** Produção (`fnrh.turismo.serpro.gov.br`). */
    PRODUCTION,
}
