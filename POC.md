Minimal Stroop Display App Requirements
1. Introduction
This document outlines the requirements for a minimal Stroop test display application designed for driver distraction research. The app serves as a simple stimulus presentation tool, with all data collection and evaluation handled externally by the moderator.
2. Application Overview
The app provides: • Configurable Stroop test stimulus display • Task sequence management • Basic timing and countdown functionality • No data recording or participant information collection
3. Functional Requirements
3.1. Configuration Management
FR-CONFIG-001: The app shall load all configuration data from an external JSON file named "research_config.json" stored in the app's assets folder.
FR-CONFIG-002: The configuration file shall contain: • Stroop color names (supporting multiple languages including German and Chinese) and their RGB color values • Task definitions (ID and timeout duration only) • Task sequence lists (predefined combinations of task IDs) • Timing parameters (Stroop display duration, min/max intervals)
FR-CONFIG-003: Configuration file structure:
{
  "stroop_colors": {
    "rot": "#FF0000",
    "blau": "#0000FF", 
    "grün": "#00FF00",
    "gelb": "#FFFF00",
    "schwarz": "#000000",
    "braun": "#8B4513"
  },
  "tasks": {
    "1": {
      "timeout_seconds": 180
    },
    "2": {
      "timeout_seconds": 120
    }
  },
  "task_lists": {
    "1": {
      "label": "Basic Functions",
      "task_sequence": "1|2"
    }
  },
  "timing": {
    "stroop_display_duration": 2000,
    "min_interval": 1000,
    "max_interval": 3000,
    "countdown_duration": 4000
  }
}
Example configuration for Chinese:
{
  "stroop_colors": {
    "红": "#FF0000",
    "蓝": "#0000FF",
    "绿": "#00FF00", 
    "黄": "#FFFF00",
    "黑": "#000000",
    "棕": "#8B4513"
  }
}
FR-CONFIG-004: The app shall validate the configuration file on startup with comprehensive validation including: • Presence and basic structure of required fields (stroop_colors, tasks, task_lists, timing) • Content validation (RGB color codes in valid hex format, timeout values as positive numbers, task IDs as valid references) • Minimum requirements (at least 2 colors defined for proper Stroop conflicts) • All task IDs in task sequences must exist in the "tasks" configuration section • Display specific error messages indicating what validation failed and wait for user confirmation before closing the app
FR-CONFIG-005: Task sequences shall never contain task repetition. The pipe-delimited format (e.g., "1|2") defines the order of unique tasks only.
FR-CONFIG-006: Stroop stimuli timing shall use uniform random distribution between min and max interval values, with intervals calculated before each Stroop display.
3.2. Session Setup
FR-SETUP-001: On app startup, the app shall display available task sequence lists from the configuration file for selection.
FR-SETUP-002: The moderator shall select a task sequence list to begin the session.
3.3. Task Management
FR-TASK-001: The app shall automatically progress through tasks in the selected task sequence order.
FR-TASK-002: For each task, the app shall display on the between-tasks screen: • Task number and progress indicator (e.g., "Task 2 of 4") • "Start Task" button (before task starts)
FR-TASK-003: The app shall provide task control buttons based on current task state:
Before task starts (between-tasks screen): • "Start Task" - begins the countdown and Stroop display • "Cancel Session" - returns to task selection with confirmation • Progress indicator showing current task and total tasks
During task execution: • No control buttons available (countdown and Stroop display only)
After task completion (between-tasks screen): • "Next Task" - advances to the next task in sequence • "Restart Task" - restarts the current task from beginning with fresh randomization • "Cancel Session" - returns to task selection with confirmation • Progress indicator showing current position
Session complete (after all tasks finished): • "Restart Task" - restarts the last task • "Restart Entire Session" - restarts the entire task sequence • "Return to Main Menu" - returns to task selection
FR-TASK-004: The app shall automatically end a task when the timeout duration expires.
FR-TASK-005: After each task completion (manual end or timeout), the app shall: • Mark the task as completed in the current session • Display task completion status on the between-tasks screen • Show appropriate control buttons based on sequence position • Display "Session Complete" message when all tasks are finished
FR-TASK-006: The "Cancel Session" button shall only be available on the between-tasks screen (not during countdown or active Stroop display).
FR-TASK-007: When "Cancel Session" is pressed, the app shall display a confirmation dialog with: • Warning message: "This will cancel the current session and return to the main menu. All progress will be lost." • "Cancel" button (returns to current state) • "Confirm" button (resets all task counters and returns to task selection screen)
FR-TASK-008: Tasks shall NEVER start automatically - the moderator must manually press "Start Task" for each individual task.
FR-TASK-009: Task timeout duration determines maximum Stroop display time. The number of Stroops shown depends on display duration and random intervals. No minimum or maximum number of Stroops is enforced - if a task completes very quickly, no Stroops may be shown.
3.4. Stroop Test Display
FR-STROOP-001: When "Start Task" is pressed, the app shall display a 4-second countdown with white numbers on black background: • Display sequence: 3 → 2 → 1 (each for approximately 1.33 seconds) → 0 (briefly) • Numbers centered in the Stroop display area • Numbers sized as large as possible while remaining completely visible
FR-STROOP-002: After the countdown, the app shall begin displaying Stroop stimuli: • Color words (German, Chinese, or other languages as configured) displayed in colors different from their meaning • White background for Stroop display • White background during intervals between Stroops (no text shown) • Random intervals between Stroops (within configured min/max range using uniform distribution) • Each Stroop displayed for the configured duration
FR-STROOP-003: The app shall use purely random color assignment with the constraint that a color word never appears in its matching color (e.g., "rot" will never appear in red). Color selection is random from all available colors excluding only the matching color.
FR-STROOP-004: The app shall continue displaying Stroops until: • The task timeout expires (automatic termination)
FR-STROOP-005: Color words that may conflict with background display (such as "white" colors) should be excluded from visual display colors but may appear as text stimuli in other colors.
FR-STROOP-006: Font sizing shall be determined by testing with the longest/largest color word that needs to be displayed, then using that font size for all color words with approximately 1cm margins left and right of the text.
3.5. User Interface Requirements
FR-UI-001: The app shall force landscape orientation and prevent rotation entirely.
FR-UI-002: The interface shall be split into two main areas: • Upper area: Stroop display (large, full-width, participant-facing) • Lower area: Moderator controls (compact control bar)
FR-UI-003: Stroop stimuli shall use large, clearly visible fonts suitable for peripheral vision reading, automatically scaled based on the longest color word with 1cm margins.
FR-UI-004: The moderator control area shall show (only on between-tasks screen): • Current task number and progress indicator (e.g., "Task 2 of 4") • Context-appropriate control buttons based on task state • "Cancel Session" button
FR-UI-005: The app shall provide clear visual indicators for: • Task status (waiting to start, countdown active, Stroop display active, completed) • Which position in the task sequence is current
FR-UI-006: Between tasks, the Stroop display area shall show a black screen.
FR-UI-007: A small settings icon shall be visible only on the initial task selection screen to access timing configuration.
3.6. Settings Interface
FR-SETTINGS-001: The app shall provide a settings screen accessible only from the initial task selection screen.
FR-SETTINGS-002: The settings screen shall allow real-time adjustment of: • Stroop display duration (in milliseconds) • Minimum interval between Stroops (in milliseconds) • Maximum interval between Stroops (in milliseconds) • Countdown duration (in seconds)
FR-SETTINGS-003: Settings changes shall be stored locally (not modifying the original JSON configuration file) and persist across app restarts, taking precedence over JSON config values.
FR-SETTINGS-004: The settings screen shall include "Reset to Defaults" button to restore original configuration file values.
FR-SETTINGS-005: Navigation to settings shall preserve current session state, allowing researchers to check settings mid-session without losing progress.
4. Non-Functional Requirements
4.1. Performance
NFR-PERF-001: Stroop display timing shall be accurate within ±50ms of configured intervals.
NFR-PERF-002: User interface shall remain responsive during Stroop display.
NFR-PERF-003: Transitions between Stroops shall be smooth without flicker.
4.2. Usability
NFR-USE-001: All text shall use proper UTF-8 encoding to support multiple languages including German, Chinese, and other character sets.
NFR-USE-002: The app shall use a single system font that supports all configured languages with automatic fallback handling for unsupported characters.
NFR-USE-003: The interface shall be simple enough for quick moderator operation during research sessions.
NFR-USE-004: Task controls shall be large enough for easy access without interfering with Stroop display.
4.3. Reliability
NFR-REL-001: The app shall handle configuration file errors gracefully with specific error messages.
NFR-REL-002: Timing shall be consistent across different Android devices.
NFR-REL-003: The app shall continue functioning properly if tasks are restarted or the sequence is reset multiple times.
4.4. Compatibility
NFR-COMP-001: The app shall target Android 8.0 (API level 26) minimum for broad device compatibility.
NFR-COMP-002: The app shall function on devices with varying screen sizes while maintaining readable Stroop text through automatic font scaling.
5. Implementation Notes
5.1. Technical Architecture
• UI Framework: Traditional Android XML layouts • Architecture: Simple MVVM pattern for state management • Threading: Kotlin Coroutines for timing operations • Configuration: Asset-based JSON loading • State Management: In-memory only (no persistence needed)
5.2. Key Simplifications
• No data storage: All evaluation and recording handled externally • No participant information: App is session-agnostic • Minimal state tracking: Only current task position and completion status • No export functionality: App serves purely as stimulus display tool • Language agnostic: Supports any language through configuration files
5.3. Development Priority
Phase 1: Core stimulus display • Configuration loading and comprehensive validation • Basic Stroop display with timing and proper color assignment • Simple task sequence navigation • Settings persistence
Phase 2: Polish and usability • Settings interface for timing adjustments • Improved visual feedback • Error handling and edge cases
5.4. Multiple Language Support
Configuration files can contain color names in any language using UTF-8 encoding: • App automatically adapts to display whatever color names are configured • Single system font rendering supports various writing systems (Latin, Chinese, etc.) • User interface elements (buttons, menus) remain in English for consistency • No hardcoded language-specific elements for Stroop content in the app code
Implementation Notes: • Use system fonts that support the target languages • Test with both simplified and traditional Chinese characters • Ensure proper text sizing for different character densities • Validate UTF-8 handling throughout the app
6. Usage Flow
1.	Session Setup: Moderator selects task sequence list on app startup
2.	Task Preparation: App displays current task number and "Start Task" button on between-tasks screen
3.	Task Execution: Moderator presses "Start Task" when ready
4.	Countdown Display: App displays 4-second countdown (3, 2, 1, 0) with white numbers on black background
5.	Stimulus Display: App begins Stroop sequence with colored text on white background
6.	Data Collection: Moderator observes participant and records data externally
7.	Task Completion: Task times out automatically
8.	Task Review: App shows completion status on between-tasks screen with "Next Task" and "Restart Task" options
9.	Sequence Management: Moderator chooses to advance or repeat current task
10.	Session Control: Process repeats until all tasks complete, or moderator cancels session
11.	Session Reset: Moderator can start over with new task sequence selection
Key Control Features: • Manual start required: Each task must be manually started by moderator • Flexible task management: Tasks can be restarted with fresh randomization if needed • Session control: Moderator can cancel and restart entire session at any time from between-tasks screen • Clear confirmation: Cancellation requires confirmation to prevent accidental resets • Settings access: Available only from initial screen, preserves session state
This approach gives moderators complete control over pacing and allows for handling various research scenarios (participant needs break, task needs to be repeated, equipment issues, etc.).

