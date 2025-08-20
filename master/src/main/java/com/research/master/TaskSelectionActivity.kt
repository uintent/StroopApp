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
import com.research.shared.models.TaskConfig
import com.research.shared.models.TaskListConfig
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Activity for selecting individual tasks or task lists
 * Displays tasks and task lists loaded from configuration file
 */
class TaskSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskSelectionBinding
    private lateinit var tasksAdapter: TasksAdapter
    private lateinit var taskListsAdapter: TaskListsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTaskSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        supportActionBar?.title = "Select Task"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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
            val tasks = config.baseConfig.tasks
            tasksAdapter.updateTasks(tasks)

            // Load task lists
            val taskLists = config.baseConfig.taskLists
            taskListsAdapter.updateTaskLists(taskLists, tasks) // Pass tasks for validation

            // Update UI visibility
            updateSectionVisibility(tasks, taskLists)

            Log.d("TaskSelection", "Loaded ${tasks.size} tasks and ${taskLists.size} task lists")
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

        // Show/hide task lists section
        if (taskLists.isNotEmpty()) {
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
     */
    private fun onIndividualTaskSelected(taskId: String, taskConfig: TaskConfig) {
        Log.d("TaskSelection", "Individual task selected: $taskId - ${taskConfig.label}")

        // TODO: Navigate to Task Control Screen (ColorDisplayActivity for now)
        val intent = Intent(this, ColorDisplayActivity::class.java).apply {
            putExtra("TASK_ID", taskId)
            putExtra("TASK_LABEL", taskConfig.label)
            putExtra("TASK_TEXT", taskConfig.text)
            putExtra("TASK_TIMEOUT", taskConfig.timeoutSeconds)
            putExtra("IS_INDIVIDUAL_TASK", true)
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

    /**
     * Adapter for individual tasks
     */
    private class TasksAdapter(
        private val onTaskClick: (String, TaskConfig) -> Unit
    ) : RecyclerView.Adapter<TasksAdapter.TaskViewHolder>() {

        private var tasks = mapOf<String, TaskConfig>()

        fun updateTasks(newTasks: Map<String, TaskConfig>) {
            tasks = newTasks.toSortedMap() // Sort by task ID
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_card, parent, false)
            return TaskViewHolder(view, onTaskClick)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            val taskEntry = tasks.entries.elementAt(position)
            holder.bind(taskEntry.key, taskEntry.value)
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

            fun bind(taskId: String, taskConfig: TaskConfig) {
                textTitle.text = taskConfig.label
                textDescription.text = taskConfig.text
                textTimeout.text = "Timeout: ${taskConfig.timeoutSeconds}s"

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