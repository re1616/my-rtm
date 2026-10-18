// Taskly Web — Task Detail Page (shell)
// Three-panel detail panel that renders the TaskCard for a selected task
import React, { useState, useEffect } from 'react';
import type { Task } from '../../types';
import { getTaskById } from '../../entities/taskRepository';
import TaskCard from './TaskCard';

interface TaskDetailPageProps {
  taskId: string;
  onClose: () => void;
}

const TaskDetailPage: React.FC<TaskDetailPageProps> = ({ taskId, onClose }) => {
  const [task, setTask] = useState<Task | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function load() {
      setLoading(true);
      const t = await getTaskById(taskId);
      setTask(t);
      setLoading(false);
    }
    load();
  }, [taskId]);

  if (loading) {
    return (
      <div className="task-detail-panel">
        <div className="task-detail-loading">Загрузка...</div>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="task-detail-panel">
        <div className="task-detail-empty">Задача не найдена</div>
      </div>
    );
  }

  return (
    <div className="task-detail-panel">
      <TaskCard task={task} onClose={onClose} />
    </div>
  );
};

export default TaskDetailPage;