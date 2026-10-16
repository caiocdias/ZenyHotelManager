package com.zeny.hotel.notifications

/** Plataforma do aparelho que recebe o push pelo FCM. */
enum class PushPlatform {
    ANDROID,
    IOS,
    WEB,
}

/** Por que um dispositivo deixou de receber push. */
enum class PushDeactivationReason {
    /** O próprio usuário o desativou. */
    USER,

    /** O mesmo token, ou a mesma sessão, foi registrado de novo (token renovado, aparelho que trocou de mãos). */
    REPLACED,

    /** O FCM disse que o token não vale mais (`UNREGISTERED`, `SENDER_ID_MISMATCH`, `INVALID_ARGUMENT`). */
    INVALID_TOKEN,

    /** A sessão de dispositivo do `iam` foi revogada ou venceu. */
    SESSION_ENDED,
}
