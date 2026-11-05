package com.zeny.hotel.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DomainErrorsTest {
    @Test
    fun `error codes are upper snake case`() {
        ErrorCode("ROOM_NOT_READY").value shouldBe "ROOM_NOT_READY"
        ErrorCode("LIMIT_EXCEEDED_2").value shouldBe "LIMIT_EXCEEDED_2"

        listOf("", "room_not_ready", "Room", "ROOM-NOT-READY", "_ROOM", "ROOM NOT", "2ROOM").forEach { invalid ->
            shouldThrow<IllegalArgumentException> { ErrorCode(invalid) }
        }
    }

    @Test
    fun `each exception family carries its kind`() {
        val code = ErrorCode("SOMETHING")

        InvalidInputException(code, "m").kind shouldBe ErrorKind.INVALID_INPUT
        UnauthenticatedException(code, "m").kind shouldBe ErrorKind.UNAUTHENTICATED
        NotFoundException(code, "m").kind shouldBe ErrorKind.NOT_FOUND
        ConflictException(code, "m").kind shouldBe ErrorKind.CONFLICT
        BusinessRuleException(code, "m").kind shouldBe ErrorKind.RULE_VIOLATION
        PermissionDeniedException(code, "m").kind shouldBe ErrorKind.PERMISSION_DENIED
        PreconditionFailedException(code, "m").kind shouldBe ErrorKind.PRECONDITION_FAILED
        PreconditionRequiredException(code, "m").kind shouldBe ErrorKind.PRECONDITION_REQUIRED
    }

    @Test
    fun `invalid input may carry field violations`() {
        val violation = FieldViolation("name", FieldViolationCodes.REQUIRED, "Informe o nome.")

        val error = InvalidInputException(ErrorCodes.VALIDATION_FAILED, "Campos inválidos.", listOf(violation))

        error.message shouldBe "Campos inválidos."
        error.violations shouldContainExactly listOf(violation)
    }

    @Test
    fun `the gateway families carry their kind`() {
        val code = ErrorCode("SOMETHING")

        BadGatewayException(code, "m").kind shouldBe ErrorKind.BAD_GATEWAY
        ServiceUnavailableException(code, "m").kind shouldBe ErrorKind.SERVICE_UNAVAILABLE
    }

    @Test
    fun `the gateway kinds map to 502 and 503`() {
        com.zeny.hotel.shared.web.Problems
            .statusOf(ErrorKind.BAD_GATEWAY)
            .value() shouldBe 502
        com.zeny.hotel.shared.web.Problems
            .statusOf(ErrorKind.SERVICE_UNAVAILABLE)
            .value() shouldBe 503
    }
}
