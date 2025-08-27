package com.research.master

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivityTaskSelectionBinding
import com.research.master.utils.MasterConfigManager
import com.research.master.utils.SessionManager
import com.research.master.utils.FileManager
import com.research.master.utils.DebugLogger
import com.research.shared.models.TaskConfig
import com.research.shared.models.TaskListConfig
import kotlinx.coroutines.launch

/**
 * Activity for selecting individual tasks or task lists
 * ENHANCED: Now supports task list mode - shows only tasks from a specific list in configured order
 * Maintains task completion status with visual indicators
 * Tasks remain selectable regardless of completion status
 */
class TaskSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskSelectionBinding
    private lateinit var tasksAdapter: TasksAdapter
    private lateinit var taskListsAdapter: TaskListsAdapter
    private lateinit var fileManager: FileManager

    // Task list context
    private var taskListId: String? = null
    private var taskListLabel: String? = null
    private var isTaskListMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTaskSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FileManager
        fileManager = FileManager(this)

        // Check if we're in task list mode
        taskListId = intent.getStringExtra("TASK_LIST_ID")
        taskListLabel = intent.getStringExtra("TASK_LIST_LABEL")
        isTaskListMode = taskListId != null

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = if (isTaskListMode) {
            taskListLabel ?: getString(R.string.task_selection_task_list_title_format, taskListId ?: "")
        } else {
            getString(R.string.task_selection_title)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Set up return to session manager button - modify label in task list mode
        if (isTaskListMode) {
            binding.btnReturnToSessionManager.text = getString(R.string.task_selection_return_to_task_lists)
        }
        binding.btnReturnToSessionManager.setOnClickListener {
            if (isTaskListMode) {
                returnToTaskLists()
            } else {
                returnToSessionManager()
            }
        }

        // Set up RecyclerViews
        setupRecyclerViews()

        // Load and display tasks/task lists
        loadTasksAndTaskLists()

        // Set up observers
        setupObservers()
    }

    private fun setupRecyclerViews() {
        // Individual Tasks RecyclerView
        tasksAdapter = TasksAdapter { taskId, taskConfig ->
            onIndividualTaskSelected(taskId, taskConfig)
        }

        binding.recyclerTasks.apply {
            layoutManager = LinearLayoutManager(this@TaskSelectionActivity)
            adapter = tasksAdapter
        }

        // Task Lists RecyclerView
        taskListsAdapter = TaskListsAdapter { taskListId, taskListConfig ->
            onTaskListSelected(taskListId, taskListConfig)
        }

        binding.recyclerTaskLists.apply {
            layoutManager = LinearLayoutManager(this@TaskSelectionActivity)
            adapter = taskListsAdapter
        }
    }

    private fun loadTasksAndTaskLists() {
        lifecycleScope.launch {
            val config = MasterConfigManager.getCurrentConfig()
            if (config == null) {
                showConfigError()
                return@launch
            }

            val allTasks = config.baseConfig.tasks

            if (isTaskListMode && taskListId != null) {
                // Task list mode - show only tasks from the specified list in order
                loadTaskListMode(allTasks)
            } else {
                // Global mode - show all tasks and task lists
                loadGlobalMode(allTasks, config.baseConfig.taskLists)
            }
        }
    }

    /**
     * Load tasks for a specific task list in the order defined in the config
     */
    private suspend fun loadTaskListMode(allTasks: Map<String, TaskConfig>) {
        val config = MasterConfigManager.getCurrentConfig()!!
        val taskListConfig = config.baseConfig.taskLists[taskListId]

        if (taskListConfig == null) {
            DebugLogger.e("TaskSelection", "Task list '$taskListId' not found in config")
            showConfigError(getString(R.string.task_selection_config_error_format, "Task list '$taskListId' not found"))
            return
        }

        // Get task IDs in the order specified in the config
        val taskIds = taskListConfig.getTaskIds()
        DebugLogger.d("TaskSelection", "Task list '$taskListId' contains tasks: $taskIds")

        // Create ordered map of tasks (LinkedHashMap preserves insertion order)
        val orderedTasks = linkedMapOf<String, TaskConfig>()
        taskIds.forEach { taskId ->
            allTasks[taskId]?.let { taskConfig ->
                orderedTasks[taskId] = taskConfig
            } ?: run {
                DebugLogger.w("TaskSelection", "Task '$taskId' from list '$taskListId' not found in config")
            }
        }

        // Update adapter with ordered tasks
        tasksAdapter.updateTasks(orderedTasks)

        // Hide task lists section in task list mode
        taskListsAdapter.updateTaskLists(emptyMap(), allTasks)

        // Check completion status
        checkTaskCompletionStatus(orderedTasks)

        // Update UI visibility
        updateSectionVisibility(orderedTasks, emptyMap())

        DebugLogger.d("TaskSelection", "Task list mode: loaded ${orderedTasks.size} tasks from list '$taskListId'")
    }

    /**
     * Load all tasks and task lists for global selection mode
     */
    private suspend fun loadGlobalMode(allTasks: Map<String, TaskConfig>, taskLists: Map<String, TaskListConfig>) {
        // Show all tasks sorted by ID
        val sortedTasks = allTasks.toSortedMap()
        tasksAdapter.updateTasks(sortedTasks)

        // Show all task lists
        taskListsAdapter.updateTaskLists(taskLists, allTasks)

        // Check completion status
        checkTaskCompletionStatus(sortedTasks)

        // Update UI visibility
        updateSectionVisibility(sortedTasks, taskLists)

        DebugLogger.d("TaskSelection", "Global mode: loaded ${sortedTasks.size} tasks and ${taskLists.size} task lists")
    }

    /**
     * Check completion status for displayed tasks
     */
    private fun checkTaskCompletionStatus(tasks: Map<String, TaskConfig>) {
        lifecycleScope.launch {
            try {
                val currentSession = SessionManager.currentSession.value
                if (currentSession != null) {
                    val sessionFile = SessionManager.getCurrentSessionFile()
                    if (sessionFile != null && sessionFile.exists()) {
                        // Convert task IDs to integers for FileManager
                        val taskNumbers = tasks.keys.mapNotNull { taskId ->
                            try {
                                taskId.toInt()
                            } catch (e: NumberFormatException) {
                                DebugLogger.w("TaskSelection", "Invalid task ID format: $taskId")
                                null
                            }
                        }

                        if (taskNumbers.isNotEmpty()) {
                            val completionStatus = fileManager.getTaskListCompletionStatus(sessionFile, taskNumbers)
                            tasksAdapter.updateCompletionStatus(completionStatus)

                            DebugLogger.d("TaskSelection", "Updated completion status for ${completionStatus.size} tasks")
                            completionStatus.forEach { (taskNum, status) ->
                                DebugLogger.d("TaskSelection", "  Task $taskNum: $status")
                            }
                        }
                    } else {
                        DebugLogger.d("TaskSelection", "No session file available for completion status")
                    }
                } else {
                    DebugLogger.d("TaskSelection", "No active session for completion status")
                }
            } catch (e: Exception) {
                DebugLogger.e("TaskSelection", "Failed to check task completion status", e)
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            MasterConfigManager.configState.collect { configState ->
                when (configState) {
                    is MasterConfigManager.ConfigState.Error -> {
                        showConfigError(configState.message)
                    }
                    is MasterConfigManager.ConfigState.NotLoaded -> {
                        showConfigError("Configuration not loaded")
                    }
                    else -> {
                        // Configuration is loaded or loading - UI will be updated by loadTasksAndTaskLists
                    }
                }
            }
        }
    }

    private fun updateSectionVisibility(tasks: Map<String, TaskConfig>, taskLists: Map<String, TaskListConfig>) {
        // Show/hide individual tasks section
        if (tasks.isNotEmpty()) {
            binding.sectionTasks.visibility = View.VISIBLE
            binding.textNoTasks.visibility = View.GONE
        } else {
            binding.sectionTasks.visibility = View.GONE
            binding.textNoTasks.visibility = View.VISIBLE
        }

        // Show/hide task lists section (hidden in task list mode)
        if (taskLists.isNotEmpty() && !isTaskListMode) {
            binding.sectionTaskLists.visibility = View.VISIBLE
            binding.textNoTaskLists.visibility = View.GONE
        } else {
            binding.sectionTaskLists.visibility = View.GONE
            binding.textNoTaskLists.visibility = View.VISIBLE
        }

        // Show overall empty state if both are empty
        if (tasks.isEmpty() && taskLists.isEmpty()) {
            binding.textEmptyState.visibility = View.VISIBLE
            binding.scrollViewContent.visibility = View.GONE
        } else {
            binding.textEmptyState.visibility = View.GONE
            binding.scrollViewContent.visibility = View.VISIBLE
        }
    }

    /**
     * Handle individual task selection - go directly to Task Control Screen
     * UPDATED: Passes task list context when in task list mode
     */
    private fun onIndividualTaskSelected(taskId: String, taskConfig: TaskConfig) {
        DebugLogger.d("TaskSelection", "Individual task selected: $taskId - ${taskConfig.label}")

        // Convert String taskId to Int for FileManager compatibility
        val taskNumber = try {
            taskId.toInt()
        } catch (e: NumberFormatException) {
            DebugLogger.e("TaskSelection", "Invalid task ID format: $taskId, expected integer")
            Snackbar.make(
                binding.root,
                getString(R.string.task_selection_invalid_task_id_format, taskId),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        // Navigate to Task Control Screen (ColorDisplayActivity)
        val intent = Intent(this, ColorDisplayActivity::class.java).apply {
            putExtra("TASK_NUMBER", taskNumber)
            putExtra("TASK_ID", taskId)
            putExtra("TASK_LABEL", taskConfig.label)
            putExtra("TASK_TEXT", taskConfig.text)
            putExtra("TASK_TIMEOUT", taskConfig.timeoutSeconds)
            putExtra("IS_INDIVIDUAL_TASK", !isTaskListMode) // False if part of a task list
            // Pass task list context if we're in task list mode
            if (isTaskListMode) {
                putExtra("TASK_LIST_ID", taskListId)
                putExtra("TASK_LIST_LABEL", taskListLabel)
            }
        }
        startActivity(intent)
    }

    /**
     * Handle task list selection - navigate to task list view
     * UPDATED: Now actually navigates to task list mode instead of showing placeholder
     */
    private fun onTaskListSelected(taskListId: String, taskListConfig: TaskListConfig) {
        DebugLogger.d("TaskSelection", "Task list selected: $taskListId - ${taskListConfig.label}")

        // Navigate to the same activity but in task list mode
        val intent = Intent(this, TaskSelectionActivity::class.java).apply {
            putExtra("TASK_LIST_ID", taskListId)
            putExtra("TASK_LIST_LABEL", taskListConfig.label)
        }
        startActivity(intent)
    }

    /**
     * Return to main task lists view (when in task list mode)
     */
    private fun returnToTaskLists() {
        DebugLogger.d("TaskSelection", "Returning to Task Lists")
        // Simply finish this activity to return to the previous task selection screen
        finish()
    }

    /**
     * Return to Session Manager screen (when in global mode)
     */
    private fun returnToSessionManager() {
        DebugLogger.d("TaskSelection", "Returning to Session Manager")

        val intent = Intent(this, MainSessionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun showConfigError(message: String = "Configuration error") {
        binding.scrollViewContent.visibility = View.GONE
        binding.textEmptyState.visibility = View.VISIBLE
        binding.textEmptyState.text = getString(R.string.task_selection_config_error_format, message)

        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (isTaskListMode) {
            returnToTaskLists()
        } else {
            returnToSessionManager()
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        // Refresh task completion status when returning to this activity
        lifecycleScope.launch {
            val config = MasterConfigManager.getCurrentConfig()
            if (config != null) {
                if (isTaskListMode && taskListId != null) {
                    val taskListConfig = config.baseConfig.taskLists[taskListId]
                    if (taskListConfig != null) {
                        val taskIds = taskListConfig.getTaskIds()
                        val orderedTasks = linkedMapOf<String, TaskConfig>()
                        taskIds.forEach { taskId ->
                            config.baseConfig.tasks[taskId]?.let { taskConfig ->
                                orderedTasks[taskId] = taskConfig
                            }
                        }
                        checkTaskCompletionStatus(orderedTasks)
                    }
                } else {
                    checkTaskCompletionStatus(config.baseConfig.tasks)
                }
            }
        }
    }

    /**
     * Adapter for individual tasks
     * ENHANCED: Shows task position in list when in task list mode
     */
    private class TasksAdapter(
        private val onTaskClick: (String, TaskConfig) -> Unit
    ) : RecyclerView.Adapter<TasksAdapter.TaskViewHolder>() {

        private var tasks = linkedMapOf<String, TaskConfig>()
        private var completionStatus = mapOf<Int, FileManager.TaskCompletionStatus>()

        fun updateTasks(newTasks: Map<String, TaskConfig>) {
            tasks = if (newTasks is LinkedHashMap) {
                LinkedHashMap(newTasks)
            } else {
                LinkedHashMap(newTasks.toSortedMap())
            }
            notifyDataSetChanged()
        }

        fun updateCompletionStatus(newStatus: Map<Int, FileManager.TaskCompletionStatus>) {
            completionStatus = newStatus
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_card, parent, false)
            return TaskViewHolder(view, onTaskClick)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            val taskEntry = tasks.entries.elementAt(position)
            val taskNumberInt = try {
                taskEntry.key.toInt()
            } catch (e: NumberFormatException) {
                null
            }
            val status = taskNumberInt?.let { completionStatus[it] }
            holder.bind(taskEntry.key, taskEntry.value, status, position + 1) // 1-based position
        }

        override fun getItemCount() = tasks.size

        class TaskViewHolder(
            itemView: View,
            private val onClick: (String, TaskConfig) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {

            private val cardView: MaterialCardView = itemView.findViewById(R.id.card_task)
            private val textTitle: TextView = itemView.findViewById(R.id.text_task_title)
            private val textDescription: TextView = itemView.findViewById(R.id.text_task_description)
            private val textTimeout: TextView = itemView.findViewById(R.id.text_task_timeout)

            fun bind(taskId: String, taskConfig: TaskConfig, completionStatus: FileManager.TaskCompletionStatus?, position: Int) {
                val context = itemView.context

                // Show task number and label with position indicator in list mode
                var titleText = context.getString(R.string.task_selection_task_position_format, position, taskId, taskConfig.label)

                // Determine if task is completed (any end condition)
                val isCompleted = when (completionStatus) {
                    is FileManager.TaskCompletionStatus.Successful,
                    is FileManager.TaskCompletionStatus.CompletedOther -> true
                    else -> false
                }

                // Apply greyed out styling for completed tasks
                if (isCompleted) {
                    textTitle.alpha = 0.6f
                    textDescription.alpha = 0.6f
                    textTimeout.alpha = 0.6f
                    cardView.alpha = 0.8f
                } else {
                    textTitle.alpha = 1.0f
                    textDescription.alpha = 1.0f
                    textTimeout.alpha = 1.0f
                    cardView.alpha = 1.0f
                }

                // Add completion indicator to title
                when (completionStatus) {
                    is FileManager.TaskCompletionStatus.Successful -> {
                        titleText += context.getString(R.string.task_selection_task_successful_indicator)
                        cardView.strokeColor = context.getColor(android.R.color.holo_green_light)
                        cardView.strokeWidth = 3
                    }
                    is FileManager.TaskCompletionStatus.CompletedOther -> {
                        titleText += context.getString(R.string.task_selection_task_other_format, completionStatus.endCondition)
                        cardView.strokeColor = context.getColor(android.R.color.holo_orange_light)
                        cardView.strokeWidth = 3
                    }
                    is FileManager.TaskCompletionStatus.InProgress -> {
                        titleText += context.getString(R.string.task_selection_task_in_progress_indicator)
                        cardView.strokeColor = context.getColor(android.R.color.holo_blue_light)
                        cardView.strokeWidth = 2
                    }
                    else -> {
                        // NotStarted or null - no indicator
                        cardView.strokeWidth = 0
                    }
                }

                textTitle.text = titleText
                textDescription.text = taskConfig.text
                textTimeout.text = context.getString(R.string.task_selection_timeout_format, taskConfig.timeoutSeconds)

                // Visual indicator if task ID is not a valid integer
                val isValidInteger = try {
                    taskId.toInt()
                    true
                } catch (e: NumberFormatException) {
                    false
                }

                if (!isValidInteger) {
                    textTitle.text = "$titleText ${context.getString(R.string.task_selection_task_invalid_id_warning)}"
                    if (cardView.strokeWidth == 0) {
                        cardView.strokeWidth = 3
                        cardView.strokeColor = context.getColor(android.R.color.holo_red_light)
                    }
                }

                cardView.setOnClickListener {
                    onClick(taskId, taskConfig)
                }
            }
        }
    }

    /**
     * Adapter for task lists (only used in global mode)
     */
    private class TaskListsAdapter(
        private val onTaskListClick: (String, TaskListConfig) -> Unit
    ) : RecyclerView.Adapter<TaskListsAdapter.TaskListViewHolder>() {

        private var taskLists = mapOf<String, TaskListConfig>()
        private var allTasks = mapOf<String, TaskConfig>()

        fun updateTaskLists(newTaskLists: Map<String, TaskListConfig>, tasks: Map<String, TaskConfig>) {
            taskLists = newTaskLists.toSortedMap() // Sort by task list ID
            allTasks = tasks
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskListViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_list_card, parent, false)
            return TaskListViewHolder(view, onTaskListClick)
        }

        override fun onBindViewHolder(holder: TaskListViewHolder, position: Int) {
            val taskListEntry = taskLists.entries.elementAt(position)
            holder.bind(taskListEntry.key, taskListEntry.value, allTasks)
        }

        override fun getItemCount() = taskLists.size

        class TaskListViewHolder(
            itemView: View,
            private val onClick: (String, TaskListConfig) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {

            private val cardView: MaterialCardView = itemView.findViewById(R.id.card_task_list)
            private val textTitle: TextView = itemView.findViewById(R.id.text_task_list_title)
            private val textTaskCount: TextView = itemView.findViewById(R.id.text_task_count)
            private val textTaskPreview: TextView = itemView.findViewById(R.id.text_task_preview)

            fun bind(taskListId: String, taskListConfig: TaskListConfig, allTasks: Map<String, TaskConfig>) {
                val context = itemView.context
                textTitle.text = taskListConfig.label

                val taskIds = taskListConfig.getTaskIds()
                textTaskCount.text = context.getString(R.string.task_selection_task_count_format, taskIds.size)

                // Show preview of task labels in order
                val taskLabels = taskIds.take(3).mapNotNull { taskId ->
                    allTasks[taskId]?.label
                }
                val preview = if (taskLabels.isNotEmpty()) {
                    taskLabels.joinToString(", ") + if (taskIds.size > 3) context.getString(R.string.task_selection_task_preview_more) else ""
                } else {
                    context.getString(R.string.task_selection_no_valid_tasks)
                }
                textTaskPreview.text = preview

                cardView.setOnClickListener {
                    onClick(taskListId, taskListConfig)
                }
            }
        }
    }
}