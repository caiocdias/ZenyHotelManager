package com.zeny.hotel.endofday

import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.NotFoundException
import java.time.LocalDate
import java.util.UUID

/** Códigos de erro do `endofday` (ARCHITECTURE §7). Um código publicado não muda de nome nem de sentido. */
object EndOfDayErrorCodes {
    /** 404: a execução do fechamento não existe. */
    val END_OF_DAY_RUN_NOT_FOUND = ErrorCode("END_OF_DAY_RUN_NOT_FOUND")

    /** 409: a data pedida já tem execução `COMPLETED`: o dia está fechado. */
    val END_OF_DAY_ALREADY_CLOSED = ErrorCode("END_OF_DAY_ALREADY_CLOSED")

    /** 409: a data pedida já tem execução `RUNNING` ou `FAILED` (RN-21: uma por data); o problema traz `runId`. */
    val END_OF_DAY_RUN_EXISTS = ErrorCode("END_OF_DAY_RUN_EXISTS")

    /** 409: a data pedida não é a operacional corrente; o problema traz `currentBusinessDate`. */
    val END_OF_DAY_DATE_MISMATCH = ErrorCode("END_OF_DAY_DATE_MISMATCH")

    /** 409: nenhuma etapa do fechamento está registrada neste servidor. */
    val END_OF_DAY_NOT_CONFIGURED = ErrorCode("END_OF_DAY_NOT_CONFIGURED")

    /** 409: a execução não está `FAILED`, então não há o que retomar. */
    val END_OF_DAY_RUN_NOT_RESUMABLE = ErrorCode("END_OF_DAY_RUN_NOT_RESUMABLE")

    /** 409: a execução está em andamento em outro executor. */
    val END_OF_DAY_RUN_IN_PROGRESS = ErrorCode("END_OF_DAY_RUN_IN_PROGRESS")
}

/** 409 `END_OF_DAY_ALREADY_CLOSED`: a data já tem execução concluída. */
class EndOfDayAlreadyClosedException(
    businessDate: LocalDate,
) : ConflictException(
        EndOfDayErrorCodes.END_OF_DAY_ALREADY_CLOSED,
        "O fechamento do dia $businessDate já foi concluído.",
    )

/**
 * 409 `END_OF_DAY_RUN_EXISTS`: a data já tem execução em andamento ou com falha. O problema traz o [runId] da execução;
 * ele é nulo só quando a execução concorrente ainda não pôde ser lida (perdeu a corrida da criação).
 */
class EndOfDayRunExistsException(
    businessDate: LocalDate,
    val runId: UUID?,
) : ConflictException(
        EndOfDayErrorCodes.END_OF_DAY_RUN_EXISTS,
        "O fechamento do dia $businessDate já tem uma execução em andamento ou com falha.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = runId?.let { mapOf("runId" to it.toString()) }.orEmpty()
}

/** 409 `END_OF_DAY_DATE_MISMATCH`: a data pedida não é a corrente; o problema traz `currentBusinessDate`. */
class EndOfDayDateMismatchException(
    val currentBusinessDate: LocalDate,
) : ConflictException(
        EndOfDayErrorCodes.END_OF_DAY_DATE_MISMATCH,
        "Só a data operacional corrente ($currentBusinessDate) pode ser fechada.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("currentBusinessDate" to currentBusinessDate.toString())
}

/** 409 `END_OF_DAY_NOT_CONFIGURED`: nenhuma etapa do fechamento registrada. */
class EndOfDayNotConfiguredException :
    ConflictException(
        EndOfDayErrorCodes.END_OF_DAY_NOT_CONFIGURED,
        "Nenhuma etapa do fechamento do dia está registrada neste servidor.",
    )

/** 404 `END_OF_DAY_RUN_NOT_FOUND`: a execução do fechamento não existe. */
class EndOfDayRunNotFoundException :
    NotFoundException(
        EndOfDayErrorCodes.END_OF_DAY_RUN_NOT_FOUND,
        "A execução do fechamento do dia não existe.",
    )

/** 409 `END_OF_DAY_RUN_NOT_RESUMABLE`: só uma execução `FAILED` ou interrompida (`RUNNING`) pode ser retomada. */
class EndOfDayRunNotResumableException :
    ConflictException(
        EndOfDayErrorCodes.END_OF_DAY_RUN_NOT_RESUMABLE,
        "A execução do fechamento do dia já foi concluída e não há o que retomar.",
    )

/** 409 `END_OF_DAY_RUN_IN_PROGRESS`: outro executor está rodando o fechamento; acompanhe a execução. */
class EndOfDayRunInProgressException :
    ConflictException(
        EndOfDayErrorCodes.END_OF_DAY_RUN_IN_PROGRESS,
        "O fechamento do dia está em andamento em outro executor.",
    )
