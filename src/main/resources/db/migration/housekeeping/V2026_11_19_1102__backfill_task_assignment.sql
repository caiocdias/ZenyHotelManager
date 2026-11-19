-- As tarefas atribuídas antes do histórico de posse (M6 E19, D3) ganham a linha da camareira atual, com o instante da
-- última escrita da tarefa. Só as que ainda não têm linha dela (o teste da migração reexecuta este comando).
INSERT INTO housekeeping.task_assignment (id, task_id, employee_id, assigned_at)
SELECT uuidv7(), t.id, t.assigned_employee_id, t.updated_at
FROM housekeeping.housekeeping_task t
WHERE t.assigned_employee_id IS NOT NULL
  AND NOT EXISTS (SELECT 1
                  FROM housekeeping.task_assignment a
                  WHERE a.task_id = t.id
                    AND a.employee_id = t.assigned_employee_id);
