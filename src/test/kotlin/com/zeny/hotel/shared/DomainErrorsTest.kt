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
}
