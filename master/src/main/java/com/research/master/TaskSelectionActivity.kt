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
import com.research.shared.models.TaskConfig
import com.research.shared.models.TaskListConfig
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Activity for selecting individual tasks or task lists
 * UPDATED: Now shows task completion status with visual indicators
 * Tasks remain selectable regardless of completion status
 * Supports both global view and task list filtering (future enhancement)
 */
class TaskSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskSelectionBinding
    private lateinit var tasksAdapter: TasksAdapter
    private lateinit var taskListsAdapter: TaskListsAdapter
    private lateinit var fileManager: FileManager

    // Task list context (for future task list filtering)
    private var taskListId: String? = null
    private var isTaskListMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTaskSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FileManager
        fileManager = FileManager(this)

        // Check if we're in task list mode (future enhancement)
        taskListId = intent.getStringExtra("TASK_LIST_ID")
        isTaskListMode = taskListId != null

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = if (isTaskListMode) "Task List: $taskListId" else "Select Task"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Set up return to session manager button
        binding.btnReturnToSessionManager.setOnClickListener {
            returnToSessionManager()
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

            // Load individual tasks
            val allTasks = config.baseConfig.tasks

            // Filter tasks if we're in task list mode (future enhancement)
            val tasksToShow = if (isTaskListMode && taskListId != null) {
                val taskListConfig = config.baseConfig.taskLists[taskListId]
                if (taskListConfig != null) {
                    val taskIds = taskListConfig.getTaskIds()
                    allTasks.filterKeys { it in taskIds }
                } else {
                    Log.w("TaskSelection", "Task list '$taskListId' not found in config")
                    allTasks
                }
            } else {
                allTasks
            }

            tasksAdapter.updateTasks(tasksToShow)

            // Load task lists (hide in task list mode)
            val taskLists = if (isTaskListMode) {
                emptyMap()
            } else {
                config.baseConfig.taskLists
            }
            taskListsAdapter.updateTaskLists(taskLists, allTasks)

            // Check task completion status if we have an active session
            checkTaskCompletionStatus(tasksToShow)

            // Update UI visibility
            updateSectionVisibility(tasksToShow, taskLists)

            Log.d("TaskSelection", "Loaded ${tasksToShow.size} tasks and ${taskLists.size} task lists")
            if (isTaskListMode) {
                Log.d("TaskSelection", "Task list mode: $taskListId")
            }
        }
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
                                Log.w("TaskSelection", "Invalid task ID format: $taskId")
                                null
                            }
                        }

                        if (taskNumbers.isNotEmpty()) {
                            val completionStatus = fileManager.getTaskListCompletionStatus(sessionFile, taskNumbers)
                            tasksAdapter.updateCompletionStatus(completionStatus)

                            Log.d("TaskSelection", "Updated completion status for ${completionStatus.size} tasks")
                            completionStatus.forEach { (taskNum, status) ->
                                Log.d("TaskSelection", "  Task $taskNum: $status")
                            }
                        }
                    } else {
                        Log.d("TaskSelection", "No session file available for completion status")
                    }
                } else {
                    Log.d("TaskSelection", "No active session for completion status")
                }
            } catch (e: Exception) {
                Log.e("TaskSelection", "Failed to check task completion status", e)
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
     * UPDATED: Converts String taskId to Int for FileManager compatibility
     */
    private fun onIndividualTaskSelected(taskId: String, taskConfig: TaskConfig) {
        Log.d("TaskSelection", "Individual task selected: $taskId - ${taskConfig.label}")

        // Convert String taskId to Int for FileManager compatibility
        val taskNumber = try {
            taskId.toInt()
        } catch (e: NumberFormatException) {
            Log.e("TaskSelection", "Invalid task ID format: $taskId, expected integer")
            Snackbar.make(
                binding.root,
                "Invalid task ID format: $taskId. Task IDs must be integers.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        // Navigate to Task Control Screen (ColorDisplayActivity)
        val intent = Intent(this, ColorDisplayActivity::class.java).apply {
            putExtra("TASK_NUMBER", taskNumber)  // CHANGED: Now passes Int instead of String
            putExtra("TASK_ID", taskId)         // Keep String version for display purposes
            putExtra("TASK_LABEL", taskConfig.label)
            putExtra("TASK_TEXT", taskConfig.text)
            putExtra("TASK_TIMEOUT", taskConfig.timeoutSeconds)
            putExtra("IS_INDIVIDUAL_TASK", true)
            // Pass task list context if we're in task list mode
            if (isTaskListMode) {
                putExtra("TASK_LIST_ID", taskListId)
            }
        }
        startActivity(intent)
    }

    /**
     * Handle task list selection - go to Task List Management Screen
     */
    private fun onTaskListSelected(taskListId: String, taskListConfig: TaskListConfig) {
        Log.d("TaskSelection", "Task list selected: $taskListId - ${taskListConfig.label}")

        // TODO: Navigate to Task List Management Screen (not implemented yet)
        // For now, show a placeholder message
        Snackbar.make(
            binding.root,
            "Task List: ${taskListConfig.label} (${taskListConfig.getTaskIds().size} tasks)\nTask List Management Screen - Coming Soon!",
            Snackbar.LENGTH_LONG
        ).show()

        // TODO: When Task List Management Screen is ready:
        // val intent = Intent(this, TaskListManagementActivity::class.java).apply {
        //     putExtra("TASK_LIST_ID", taskListId)
        //     putExtra("TASK_LIST_LABEL", taskListConfig.label)
        //     putExtra("TASK_LIST_SEQUENCE", taskListConfig.taskSequence)
        // }
        // startActivity(intent)
    }

    /**
     * Return to Session Manager screen
     */
    private fun returnToSessionManager() {
        Log.d("TaskSelection", "Returning to Session Manager")

        val intent = Intent(this, MainSessionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun showConfigError(message: String = "Configuration error") {
        binding.scrollViewContent.visibility = View.GONE
        binding.textEmptyState.visibility = View.VISIBLE
        binding.textEmptyState.text = "Error: $message\n\nPlease check configuration file."

        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Refresh task completion status when returning to this activity
        lifecycleScope.launch {
            val config = MasterConfigManager.getCurrentConfig()
            if (config != null) {
                val tasksToShow = if (isTaskListMode && taskListId != null) {
                    val taskListConfig = config.baseConfig.taskLists[taskListId]
                    if (taskListConfig != null) {
                        val taskIds = taskListConfig.getTaskIds()
                        config.baseConfig.tasks.filterKeys { it in taskIds }
                    } else {
                        config.baseConfig.tasks
                    }
                } else {
                    config.baseConfig.tasks
                }

                checkTaskCompletionStatus(tasksToShow)
            }
        }
    }

    /**
     * Adapter for individual tasks
     * UPDATED: Now shows task completion status with visual indicators
     */
    private class TasksAdapter(
        private val onTaskClick: (String, TaskConfig) -> Unit
    ) : RecyclerView.Adapter<TasksAdapter.TaskViewHolder>() {

        private var tasks = mapOf<String, TaskConfig>()
        private var completionStatus = mapOf<Int, FileManager.TaskCompletionStatus>()

        fun updateTasks(newTasks: Map<String, TaskConfig>) {
            tasks = newTasks.toSortedMap() // Sort by task ID
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
            holder.bind(taskEntry.key, taskEntry.value, status)
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

            fun bind(taskId: String, taskConfig: TaskConfig, completionStatus: FileManager.TaskCompletionStatus?) {
                // Show task number and label (e.g., "Task 1: Navigation Setup")
                var titleText = "Task $taskId: ${taskConfig.label}"

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
                        titleText += " ✓"
                        cardView.strokeColor = itemView.context.getColor(android.R.color.holo_green_light)
                        cardView.strokeWidth = 3
                    }
                    is FileManager.TaskCompletionStatus.CompletedOther -> {
                        titleText += " (${completionStatus.endCondition})"
                        cardView.strokeColor = itemView.context.getColor(android.R.color.holo_orange_light)
                        cardView.strokeWidth = 3
                    }
                    is FileManager.TaskCompletionStatus.InProgress -> {
                        titleText += " ⏳"
                        cardView.strokeColor = itemView.context.getColor(android.R.color.holo_blue_light)
                        cardView.strokeWidth = 2
                    }
                    else -> {
                        // NotStarted or null - no indicator
                        cardView.strokeWidth = 0
                    }
                }

                textTitle.text = titleText
                textDescription.text = taskConfig.text
                textTimeout.text = "Timeout: ${taskConfig.timeoutSeconds}s"

                // Visual indicator if task ID is not a valid integer
                val isValidInteger = try {
                    taskId.toInt()
                    true
                } catch (e: NumberFormatException) {
                    false
                }

                if (!isValidInteger) {
                    textTitle.text = "$titleText ⚠️ (Invalid ID)"
                    if (cardView.strokeWidth == 0) {
                        cardView.strokeWidth = 3
                        cardView.strokeColor = itemView.context.getColor(android.R.color.holo_red_light)
                    }
                }

                cardView.setOnClickListener {
                    onClick(taskId, taskConfig)
                }
            }
        }
    }

    /**
     * Adapter for task lists
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
                textTitle.text = taskListConfig.label

                val taskIds = taskListConfig.getTaskIds()
                textTaskCount.text = "${taskIds.size} tasks"

                // Show preview of task labels (now that tasks have proper labels)
                val taskLabels = taskIds.take(3).mapNotNull { taskId ->
                    allTasks[taskId]?.label
                }
                val preview = if (taskLabels.isNotEmpty()) {
                    taskLabels.joinToString(", ") + if (taskIds.size > 3) "..." else ""
                } else {
                    "No valid tasks found"
                }
                textTaskPreview.text = preview

                cardView.setOnClickListener {
                    onClick(taskListId, taskListConfig)
                }
            }
        }
    }
}