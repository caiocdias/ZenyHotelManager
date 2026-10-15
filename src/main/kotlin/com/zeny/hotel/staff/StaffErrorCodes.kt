package com.zeny.hotel.staff

import com.zeny.hotel.shared.ErrorCode

/** Códigos de erro estáveis do módulo `staff` (ARCHITECTURE §7). */
object StaffErrorCodes {
    /** 404: setor inexistente. */
    val DEPARTMENT_NOT_FOUND = ErrorCode("DEPARTMENT_NOT_FOUND")

    /** 404: colaborador inexistente. */
    val EMPLOYEE_NOT_FOUND = ErrorCode("EMPLOYEE_NOT_FOUND")

    /** 404: turno inexistente. */
    val SHIFT_NOT_FOUND = ErrorCode("SHIFT_NOT_FOUND")

    /** 404: padrão de escala inexistente (ou de outro colaborador, com escopo `OWN`). */
    val SCHEDULE_PATTERN_NOT_FOUND = ErrorCode("SCHEDULE_PATTERN_NOT_FOUND")

    /** 400: o setor informado no corpo não existe. */
    val UNKNOWN_DEPARTMENT = ErrorCode("UNKNOWN_DEPARTMENT")

    /** 400: o colaborador informado no corpo não existe. */
    val UNKNOWN_EMPLOYEE = ErrorCode("UNKNOWN_EMPLOYEE")

    /** 400: o turno informado no corpo não existe. */
    val UNKNOWN_SHIFT = ErrorCode("UNKNOWN_SHIFT")

    /** 400: o usuário a vincular ao colaborador não existe. */
    val UNKNOWN_USER = ErrorCode("UNKNOWN_USER")

    /** 422: o usuário a vincular ao colaborador está desativado. */
    val USER_INACTIVE = ErrorCode("USER_INACTIVE")

    /** 409: o usuário já está vinculado a outro colaborador (no máximo um, H7). */
    val USER_ALREADY_LINKED = ErrorCode("USER_ALREADY_LINKED")

    /** 409: já existe um setor com este nome (sem diferenciar maiúsculas). */
    val DEPARTMENT_NAME_ALREADY_IN_USE = ErrorCode("DEPARTMENT_NAME_ALREADY_IN_USE")

    /** 409: já existe um turno com este nome no setor (sem diferenciar maiúsculas). */
    val SHIFT_NAME_ALREADY_IN_USE = ErrorCode("SHIFT_NAME_ALREADY_IN_USE")

    /** 400: turno com início igual ao fim ou horário com segundos. */
    val SHIFT_TIMES_INVALID = ErrorCode("SHIFT_TIMES_INVALID")

    /** 422: colaborador desativado não recebe padrão nem entrada de escala. */
    val EMPLOYEE_INACTIVE = ErrorCode("EMPLOYEE_INACTIVE")

    /** 400: padrão incoerente (fim antes do início, dias da semana só no fixo e obrigatórios nele). */
    val SCHEDULE_PATTERN_INVALID = ErrorCode("SCHEDULE_PATTERN_INVALID")

    /** 409: o colaborador já tem um padrão ativo que se sobrepõe ao período. */
    val SCHEDULE_PATTERN_OVERLAP = ErrorCode("SCHEDULE_PATTERN_OVERLAP")

    /** 422: padrão desativado não é editado. */
    val SCHEDULE_PATTERN_INACTIVE = ErrorCode("SCHEDULE_PATTERN_INACTIVE")

    /** 400: período com fim antes do início ou maior que o máximo. */
    val SCHEDULE_PERIOD_INVALID = ErrorCode("SCHEDULE_PERIOD_INVALID")

    /** 422: a geração da escala não reescreve datas anteriores à data operacional. */
    val SCHEDULE_PERIOD_IN_PAST = ErrorCode("SCHEDULE_PERIOD_IN_PAST")

    /** 400: entrada de escala incoerente (turno obrigatório só para trabalho e troca). */
    val SCHEDULE_ENTRY_INVALID = ErrorCode("SCHEDULE_ENTRY_INVALID")

    /** 400: nome, cargo ou contato em branco ou grande demais. */
    val STAFF_DATA_INVALID = ErrorCode("STAFF_DATA_INVALID")
}
