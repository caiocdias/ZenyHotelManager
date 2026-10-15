package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamApi
import com.zeny.hotel.iam.UserAccountSummary
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A API pública do `iam` ([IamApi]): leituras sem checar permissão, na transação de quem chama. */
@Service
class IamApiService(
    private val users: Users,
) : IamApi {
    @Transactional(readOnly = true)
    override fun findUser(id: UserId): UserAccountSummary? =
        users.findById(id)?.let { UserAccountSummary(it.id, it.name, it.active) }
}
