Requirements Document: Driver Distraction Measurement System
1. Introduction
This document outlines the requirements for a two-part mobile application system, "Master" and "Projector," designed to measure driver distraction, time on task, and task completion rates during specific in-car tasks. The system will also gather user experience feedback using a simplified ASQ questionnaire.
The two applications will communicate wirelessly via local WiFi network using Network Service Discovery (NSD) to facilitate the testing process and data collection.
2. Application Overview
The system consists of two distinct applications, each with a specific role:
2.1. Master Application (Moderator App)
The Master app will run on a mobile device and be used by a moderator. Its primary functions include:
•	Initiating, controlling, and terminating tasks
•	Managing participant and session details
•	Receiving real-time data from the Projector app
•	Recording moderator observations and participant feedback
•	Storing all collected data in a structured JSON format
•	Providing settings for test parameters
2.2. Projector Application (Participant App)
The Projector app will run on a separate mobile device and display visual stimuli to the participant. Its primary functions include:
•	Displaying countdowns and Stroop test stimuli as commanded by the Master app
•	Operating as a controlled display device without data collection or storage
•	Receiving task control commands from the Master app via local WiFi network
2.3. Inter-App Communication Protocol
The Master and Projector applications will communicate via local WiFi network using Network Service Discovery (NSD) with TCP sockets for reliable data transmission.
Communication Architecture:
•	Master app acts as TCP client, discovers and connects to Projector services
•	Projector app acts as TCP server, advertising its service via NSD
•	Master app controls all Stroop sequencing and data collection
•	Projector app displays stimuli as commanded by Master app
•	JSON message format for simple command transmission
•	Automatic device discovery and identification through service names
Multi-Device Environment Support:
•	Device Identification: Each Projector app advertises a unique service name combining: 
o	Base service name: "DriverDistraction_Projector"
o	Team/researcher identifier (configurable)
o	Device description (e.g., "Team1_ProjectorA", "Team2_ProjectorB")
•	Service Discovery: Master app scans for all DriverDistraction services on the network
•	Device Selection: Master app displays discovered devices with signal strength and status for manual selection
•	Network Requirements: Both devices must be connected to the same local WiFi network
NSD Service Structure:
Service Type: "_drivertest._tcp."
Service Names: 
  - "DriverDistraction_Team1_DeviceA" 
  - "DriverDistraction_Team2_DeviceB"
  - etc.
Connection Flow:
1.	Projector app starts TCP server on available port
2.	Projector advertises service via NSD with team/device identifier
3.	Master app discovers services using NSD service discovery
4.	Master displays discovered devices for moderator selection
5.	Master connects to selected Projector via TCP socket
6.	Master sends task control commands to Projector via JSON messages
7.	Automatic reconnection if connection drops
Data Transmission Strategy:
•	Command-based communication: Master sends display commands to Projector
•	No data collection on Projector: All evaluation and data recording handled by Master app
•	Simple command structure: Task start/stop, Stroop display commands, timeout information
•	Connection monitoring: Heartbeat messages to detect disconnections
Example JSON message structure:
// Master → Projector: Start task with timeout
{
  "command": "START_TASK",
  "taskId": "3", 
  "timeoutSeconds": 120
}

// Master → Projector: Display specific Stroop
{
  "command": "SHOW_STROOP",
  "word": "blau",
  "color": "#FF0000",
  "duration": 2000
}

// Master → Projector: End current task
{
  "command": "END_TASK"
}
3. Functional Requirements
3.1. Session Management
FR-SM-001: The Master app shall allow the moderator to input and record the following participant details at the beginning of a session:
•	Participant Name (text)
•	Participant Identifier (alphanumeric, unique per participant)
•	Participant Age (numeric)
•	Car Model (selection: "old" or "new")
FR-SM-002: The Master app shall record the exact start time of the interview session (after participant details are set) into the session's JSON data file.
FR-SM-003: The Master app shall record the exact end time of the interview session (when explicitly ended by the moderator or all tasks completed) into the session's JSON data file.
3.2. Network Discovery and Connection
FR-CONN-001: The Projector app shall advertise its service on the local WiFi network using Android's Network Service Discovery (NSD) with service type "_drivertest._tcp."
FR-CONN-002: The Projector app shall include team and device identifiers in its service name using the format: "DriverDistraction_[TeamID]_[DeviceID]"
FR-CONN-003: The Master app shall discover all available Projector services on the local network and display them in a list for moderator selection.
FR-CONN-004: The Master app shall display for each discovered device:
•	Service name (team/device identifier)
•	Connection status (available, connected, unavailable)
•	Signal quality or response time indicator
FR-CONN-005: The Master app shall establish a TCP socket connection to the selected Projector device.
FR-CONN-006: Both apps shall implement automatic reconnection logic if the network connection is lost during a session.
FR-CONN-007: The Master app shall continuously monitor the network connection and provide clear visual feedback about connection status.
3.3. Task Management (Master App)
FR-TM-001: The Master app shall allow the moderator to manually select and start/stop individual pre-defined tasks.
FR-TM-002: As an alternative to manual selection, the Master app shall allow the moderator to select a pre-defined "task list." Upon selection, the app shall automatically proceed through each task in the list sequentially.
FR-TM-003: The Master app shall display the instruction text and the allowed maximum time for the current task whenever a task is selected or switched to.
FR-TM-003.1: Each task stored within the application shall include the following properties:
•	An alphanumeric task label (identifier)
•	A task instruction text
•	A maximum allowed time (in seconds) after which the task is considered "timed out" if no other end condition is set by the moderator
This information will be stored in an external configuration file.
FR-TM-004: The Master app shall display a countdown for the allowed task completion time to the moderator while the Projector app is displaying stimuli.
FR-TM-005: The Master app shall allow the moderator to set the end condition for a task while it is active, choosing from: "Success," "Failed," or "Give Up."
FR-TM-005.1: When any task end condition is selected, the Master app shall:
•	Save all collected Stroop response data and timing information
•	Send "END_TASK" command to Projector app
•	Display task summary screen showing complete task results
•	Provide options to proceed to next task or restart current task
FR-TM-006: The Master app shall automatically set the task end condition to "Timed Out" if the allowed maximum time for the task expires before the moderator manually sets an end condition.
FR-TM-006.1: When a task times out, the Master app shall:
•	Save all collected Stroop response data up to the timeout moment
•	Send "END_TASK" command to Projector app
•	Display task summary screen with "Timed Out" status
•	Provide same options as manually ended tasks
FR-TM-007: The Master app shall provide a summary screen after each task, displaying:
•	The task name and completion status
•	Total number of Stroops displayed
•	Number of correct and incorrect responses
•	Average reaction time
•	Individual reaction times for each Stroop
FR-TM-007.1: The Master app shall provide a "More Options" sub-menu accessible during task execution containing:
•	"Stop Task Without Rating" - discards all current task data
•	"Restart Current Task" - discards current data and restarts task from beginning
•	"End Session" - discards current task data, marks session incomplete, returns to start screen (with confirmation prompt)
FR-TM-007.2: When "Stop Task Without Rating" is selected:
•	All current task data shall be discarded
•	If working from individual task selection: return to task selection screen with task available for re-selection
•	If working from task order list: prompt moderator to choose "Repeat Current Task" or "Continue with Next Task"
FR-TM-007.3: When "Restart Current Task" is selected:
•	All current task data shall be completely discarded
•	Task shall restart from the beginning with fresh data collection
•	Same task parameters and timeout shall be used
FR-TM-007.4: When "End Session" is selected:
•	Display confirmation prompt: "This will end the entire session. All unsaved data will be lost. Are you sure?"
•	If confirmed: discard current task data, mark session as incomplete, return to participant info entry screen
•	If cancelled: return to current task state
FR-TM-008: From the task summary screen, the Master app shall provide the moderator with options to:
•	Continue to the task selection screen
•	Proceed to the next task in a pre-defined task list (if applicable)
•	Go back and change recorded data (task success rating, ASQ scores)
•	Restart the current task completely
FR-TM-009: The Master app shall provide an "Emergency Stop" button accessible at all times, allowing the moderator to cancel any ongoing process (e.g., active task, data entry). FR-TM-010: When the "Emergency Stop" button is pressed, the Master app shall display a confirmation prompt with the following options for the current task's data:
•	Record data collected so far to JSON
•	Discard current task data (remove from JSON if partially recorded, or prevent recording)
•	Return to the current process (cancel the stop action)
FR-TM-011: During the display of the cancellation prompt, task timers shall continue to run, and if the task times out during this prompt, the task end condition shall be marked as "Timed Out."
FR-TM-012: If the moderator chooses to return to the current process from the cancellation prompt, the task shall resume as if no interruption occurred.
FR-TM-013: The Master app shall allow the moderator to return to the task selection screen or task list selection screen at any time. If an active task is ongoing, this action shall trigger the same cancellation prompt and behavior as FR-TM-010.
FR-TM-014: If the same task is completed twice for a single participant in the same car during the same session, the Master app shall, at the end of the second completion, display a comparative summary of both performances for that task.
FR-TM-015: Following FR-TM-014, the Master app shall allow the moderator to select which of the two datasets for the repeated task should be retained in the JSON file, with the other being discarded or removed.
FR-TM-016: The Master app shall allow the moderator to explicitly end the overall interview process via a dedicated button available on the task summary screen or task selection screen (if task lists are not used).
FR-TM-017: The overall interview process shall also end automatically if all tasks in a selected task list have been completed.
FR-TM-018: The Master app shall provide a "Settings" button (e.g., a cog icon) accessible from relevant navigation screens (e.g., participant info entry, task selection). Clicking this button shall open a settings menu.
FR-TM-019: The settings menu in the Master app shall allow the moderator to select a local storage folder for JSON files.
FR-TM-019.1: The system shall provide a folder picker interface allowing selection of any accessible folder on the device's storage (internal or external).
FR-TM-019.2: The system shall validate read/write permissions for the selected folder before accepting the choice.
FR-TM-019.3: Selected folders must be accessible via the device's default file manager for easy file retrieval using USB cable or other standard methods.
FR-TM-019.4: The system shall use SharedPreferences to store the selected folder path persistently across app sessions.
FR-TM-019.5: If the selected folder becomes inaccessible (e.g., external storage removed), the system shall prompt the user to select a new storage location.
FR-TM-020 (Optional): Google Drive Integration
FR-TM-020.1: If automated Google Drive upload is supported, the settings menu shall provide an option to enable/disable automated upload.
FR-TM-020.2: The system shall use the credentials of the currently logged-in Android user and request permission for the app to access their Google Drive.
FR-TM-020.3: The system shall provide a Google Drive folder picker allowing users to select a destination folder within their Google Drive.
FR-TM-020.4: The system shall request the necessary authorization from the user when Google Drive access is attempted for the first time.
FR-TM-020.5: Upon completion of an interview, if Google Drive upload is enabled, the generated JSON file shall be automatically uploaded to the designated Google Drive folder while also being saved locally.
FR-TM-021: The Master app shall provide real-time evaluation controls during task execution:
FR-TM-021.1: For each Stroop displayed on the Projector, the Master app shall simultaneously display the correct color name to the moderator.
FR-TM-021.2: The Master app shall provide "Correct" and "Incorrect" buttons for the moderator to evaluate participant responses in real-time.
FR-TM-021.3: The correct color name shall be displayed on the Master app for slightly longer duration than the Stroop is shown on the Projector (e.g., 3 seconds vs 2 seconds) to allow moderator response time.
FR-TM-021.4: Reaction time shall be measured from when the Stroop appears on the Projector until when the moderator presses the evaluation button, minus a correction factor of 500 milliseconds to account for moderator reaction delay.
FR-TM-022: The settings menu shall allow the moderator to modify the duration for which each Stroop is shown on the Projector app. The default value for this setting shall be 2 seconds.
FR-TM-023: The settings menu shall allow the moderator to modify the minimum time between Stroop displays on the Projector app.
FR-TM-024: The settings menu shall allow the moderator to modify the maximum time between Stroop displays on the Projector app.
FR-TM-025: The settings menu shall include an "Apply" button. Changes made in the settings menu shall only be saved and applied when this button is pressed.
FR-TM-026: The settings menu shall include a "Reset" button. Pressing this button shall revert all settings to their state before the most recent unapplied changes were made.
FR-TM-027: The settings menu shall include a "Cancel" button. Pressing this button shall close the settings menu without saving any unapplied changes.
FR-TM-028: All settings configured by the moderator (e.g., local storage path, Stroop display times, Stroop intervals, Google Drive settings) shall be persistently stored, remaining saved even when the app is closed or the device is shut down/rebooted.
FR-TM-029: The Master app shall load hard-coded information (e.g., task texts, allowed times, Stroop colors, ASQ questions) from an external configuration file in JSON format stored in the app's assets folder.
3.4. Android Permissions and Compatibility
FR-AP-001: Both apps shall request appropriate network permissions:
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
FR-AP-002: API Level Requirements:
•	Minimum SDK: Android 8.0 (API level 26) for broad compatibility
•	Target SDK: Android 14 (API level 34) for current compliance
•	Compile SDK: Android 14 (API level 34)
FR-AP-003: Both apps shall implement foreground services for persistent research sessions:
•	Master app: ResearchSessionService maintains network connection and data collection
•	Projector app: StroopDisplayService ensures uninterrupted Stroop presentation
•	Low priority, ongoing notifications explaining research purpose
3.5. Stroop Test Display (Projector App)
FR-ST-001: The Projector app shall display a countdown (3, 2, 1, 0) for four seconds before the Stroop test begins. No other content shall be displayed during this countdown.
FR-ST-002: After the countdown finishes, the Projector app shall begin displaying Stroop stimuli.
FR-ST-003: The Projector app shall display Stroop stimuli for a duration configurable on the Master app.
FR-ST-004: The Projector app shall display Stroop stimuli at semi-random intervals, where the minimum and maximum interval times are configurable on the Master app.
FR-ST-005: The Projector app shall display Stroop stimuli as commanded by the Master app via JSON messages, with no local data collection or participant response evaluation.
FR-ST-006: The Projector app shall contain configurable Stroop colors and color names, loaded from an external configuration file. The app shall support rendering of multiple character sets including German, Chinese (Simplified and Traditional), and other Unicode characters.
FR-ST-007: The Projector app shall never display the color name in its corresponding actual color (e.g., the word "RED" will never be displayed in red ink).
FR-ST-008: Projector App Visual Requirements:
•	The Stroop stimuli shall only be displayed on the Projector app as commanded by the Master app
•	Between tasks, the Projector app shall display a black screen
•	The initial countdown (3, 2, 1, 0) shall be displayed on a black background when commanded
•	Stroop color words shall be displayed on a white background when commanded
•	A small settings icon shall be continuously visible for settings access
•	No participant instructions shall be displayed on the Projector app
FR-ST-009: When the 'Voice Recognition System Check' option (FR-TM-021) is activated on the Master app, the Projector app shall initiate a process to verify its voice recognition system is properly configured and functional.
FR-ST-010: The number of Stroops shown per task shall be dynamically determined by the task's allowed time, the individual Stroop display duration, and the semi-random intervals between Stroops. The minimum number of Stroops shown per task shall be 0.
FR-ST-011: Voice Recognition Calibration Process (Projector App) The Projector app shall, upon activation of the "Calibrate Voice Recognition" option (FR-TM-021), initiate a dedicated calibration process to optimize the voice recognition system for the individual participant's voice and the specific Stroop test vocabulary.
This process shall include:
FR-ST-011.1: Microphone Readiness Feedback: Providing visual feedback (e.g., a pulsating icon or a dynamic bar graph) that responds to the microphone's input levels to assist the participant in adjusting their speaking volume for optimal audio quality.
FR-ST-011.2: Clear Speaking Instructions: Displaying explicit, on-screen instructions in the configured language, guiding the participant to speak clearly and at a moderate pace, enunciating the color names.
FR-ST-011.3: Voice Sample Collection for Adaptation: Prompting the participant to verbally utter each of the configured color names during this phase. These samples shall be used to facilitate the voice recognition engine's adaptation to the participant's pronunciation and to bias the recognition model towards the target vocabulary.
FR-ST-012: Hybrid Voice Recognition Strategy (Projector App)
FR-ST-012.1: Primary On-Device Recognition: The Projector app shall use native Android SpeechRecognizer as the first-line voice recognition system during Stroop testing for low latency performance.
FR-ST-012.2: Decision Logic for Cloud Handover: When on-device recognition cannot determine with sufficient confidence whether the response is clearly correct or clearly incorrect, the system shall:
•	Immediately end the current Stroop display
•	Hand over the audio sample to Google Cloud Speech-to-Text for re-evaluation
•	Mark the Stroop result based on cloud recognition outcome
•	Preserve the original speech start timestamp for accurate reaction time measurement
FR-ST-012.3: Cloud Processing Behavior: During cloud handover:
•	Stroop stimulus shall disappear from screen immediately upon handover decision
•	Participant shall not be aware of the dual-processing approach
•	System shall maintain speech timing accuracy regardless of processing method
FR-ST-012.4: Confidence Threshold Management:
•	Clear correct recognition: confidence ≥ 0.8 with matching color name
•	Clear incorrect recognition: confidence ≥ 0.8 with non-matching color name
•	Uncertain recognition: confidence < 0.8 or ambiguous results trigger cloud handover
FR-ST-012.5: Global Language Optimization: The system shall configure Google Cloud Speech-to-Text with the appropriate language model and PhraseSet objects containing color names with boost values (2.0-5.0 range) to improve recognition accuracy for all participants.
FR-ST-012.6: Complete Recognition Failure: When both on-device and cloud recognition fail to produce any valid result, the Stroop shall be marked as failed and recorded as an incorrect response.
FR-ST-013: Offline Language Pack Management (Projector App) The Projector app shall programmatically check for the presence of the appropriate offline speech recognition language pack on the device. If not installed, the app shall prompt the participant to download it via system settings to ensure consistent and reliable offline performance.
3.7. Data Collection & Transfer (Projector to Master)
FR-DCT-001: The Projector app shall measure and transmit the following data points to the Master app for each task:
•	Error rate in Stroop color recognition (percentage of correctly named Stroops)
•	Error rate in Stroop color recognition (percentage of incorrectly named Stroops, including those where no valid response or an unrecognizable/incorrect color name was given)
•	Total number of Stroops shown
•	Average reaction time for all Stroops
•	Individual reaction times for each Stroop presented
FR-DCT-002: Data Transmission Timing Strategy:
FR-DCT-002.1: The Projector app shall NOT transmit Stroop data continuously during task execution.
FR-DCT-002.2: All Stroop measurement data shall be buffered locally on the Projector device during task execution.
FR-DCT-002.3: Complete data transmission shall occur only after:
•	The Projector receives task end signal from Master app, OR
•	Task timeout occurs as determined by local task timer
FR-DCT-002.4: This approach ensures task continuity even during temporary network connection interruptions.
FR-DCT-003: The reaction time for an individual Stroop shall be measured from the moment the Stroop stimulus appears on screen until the start of the participant's utterance of the correct color name. The system shall record the start time of the speech and validate the correctness of the utterance while the task timer continues running.
FR-DCT-004: If a task is completed before any Stroops are shown (i.e., 0 Stroops displayed), the Projector app shall transmit a Stroop completion rate of 100% and an average reaction time of 0 seconds for that task.
3.8. User Feedback (ASQ)
FR-UF-001: After a task is ended with the "Success" end condition (FR-TM-005), the Master app shall present two specific ASQ questions to the moderator.
FR-UF-001.1: Each ASQ question shall have a unique identifier: "ASQ_Ease" and "ASQ_Time". The exact wording for these questions will be loaded from an external configuration file (see FR-TM-029).
FR-UF-002: For each question in FR-UF-001, the Master app shall provide a 7-point scale (1 to 7) ranging from the minimum label to maximum label as defined in the configuration file.
FR-UF-003: The Master app shall allow the moderator to input the participant's stated numerical answer (1-7) for each ASQ question.
FR-UF-004: The ASQ questions and scales shall be loaded from the configuration file and can be displayed on subsequent screens if necessary.
FR-UF-005: The ASQ shall only be presented and recorded if the task's end condition is explicitly set to "Success" by the moderator.
FR-UF-006: If the ASQ is not recorded due to the task ending with a condition other than "Success" (e.g., "Failed," "Give Up," "Timed Out"), the Master app shall record "NA" for the ASQ data fields for that task in the JSON file.
3.9. Data Persistence & Export
FR-DP-001: All collected data for a participant's session shall be stored in a single JSON file.
FR-DP-002: The JSON file for each participant shall contain:
•	All participant details (name, identifier, age, car model)
•	Interview start time
•	Interview end time
•	For each task completed: 
o	Task identifier (corresponding task label)
o	Time required for task completion (from countdown end to task end) - End condition ("Failed," "Success," "Give Up," "Timed Out")
o	Error rate in Stroop color recognition (percentage correct)
o	Error rate in Stroop color recognition (percentage incorrect)
o	Total number of Stroops shown
o	Average reaction time for all Stroops
o	Individual reaction times for each Stroop
o	Recorded ASQ scores (if applicable, otherwise "NA")
o	Task end time
FR-DP-003: The JSON file shall be named using a specific format for clear participant identification: Format: [CarType][ParticipantName][ParticipantAge][Date][Time].json
•	CarType: "old" or "new" (as selected during participant setup)
•	ParticipantName: Participant name with spaces removed and special characters sanitized
•	ParticipantAge: Participant age as entered (numeric)
•	Date: Current date in German format DD.MM.YYYY
•	Time: Current time in 24-hour format HH:MM
Examples:
•	old_JohnDoe35_24.06.2025_14:30.json
•	new_MariaSchmidt42_24.06.2025_09:15.json
FR-DP-003.1: File naming validation and sanitization:
•	Participant names shall have umlauts converted (ä→ae, ö→oe, ü→ue, ß→ss)
•	Special characters shall be removed or replaced with underscores
•	Duplicate filenames shall append sequential numbers: _001, _002, etc.
•	Maximum filename length shall be validated for filesystem compatibility
FR-DP-004: File Management and Access:
FR-DP-004.1: JSON files shall be stored in user-selected folders accessible via the device's default file manager.
FR-DP-004.2: File access for moderators shall be provided through standard methods (USB cable connection, file manager access) without requiring special app functionality.
FR-DP-004.3: The system shall not implement automatic backup strategies - session recovery shall rely solely on the session completion marker detection described in FR-SR-002.
FR-DP-005 (Optional): Google Drive Upload
FR-DP-005.1: Upon completion of an interview, if Google Drive integration is enabled, the generated JSON file shall be automatically uploaded to the designated Google Drive folder.
FR-DP-005.2: Google Drive upload shall occur in addition to local file storage, not as a replacement.
FR-DP-005.3: Upload failures shall not prevent local file storage or affect session completion.
4. Non-Functional Requirements
3.10. Process Control & Timing
FR-PC-001: The countdown displayed on the Projector app (FR-ST-001) shall not count towards the task completion time.
FR-PC-002: Task timers for both the Projector (Stroop display) and Master (task duration and recorded time on task counter) shall commence only after the countdown on the Projector app has completed (reached "0").
FR-PC-003: If the moderator chooses to cancel a task (via FR-TM-010) and decides to record the data so far, the system shall record the task as it stood at the moment of cancellation, including the time taken until that point.
4. Non-Functional Requirements
4.1. Performance
NFR-PER-001: Network communication between Master and Projector apps shall be responsive, ensuring real-time data transfer without noticeable delay.
NFR-PER-002: Voice recognition shall be performed efficiently to accurately capture participant responses and measure reaction times.
NFR-PER-003: The system shall achieve consistent reaction time measurements with the 500ms moderator delay correction factor applied uniformly across all Stroop evaluations.
4.2. Usability & UI
NFR-US-001: The Projector app shall operate in landscape orientation with large, visible font sizes for Stroop stimuli.
NFR-US-002: The Master app shall operate in portrait orientation for optimal moderator usability.
NFR-US-003: All text elements shall use appropriate UTF-8 encoding to support multiple languages including German, Chinese, and other character sets.
NFR-US-004: All user interface elements (buttons, labels, menus) shall be in English and hardcoded in the application code.
4.3. Reliability & Robustness
NFR-REL-001: The Master app shall continuously monitor the network connection and provide clear visual feedback if the connection is lost.
NFR-REL-002: The system shall handle scenarios where network connections are interrupted, allowing task continuation on the Projector while maintaining data integrity on the Master app.
NFR-REL-003: Data saving to JSON shall be robust, preventing data loss in unexpected application closures or errors.
NFR-REL-004: The Master app shall provide clear visual feedback to the moderator about Projector connection status and task synchronization.
4.4. Compatibility
NFR-COMP-001: The applications shall target implementation on modern Android devices with WiFi capabilities.
NFR-COMP-002: The system shall be compatible with standard WiFi networks without requiring special network configurations.
5. Configuration Requirements & Session Recovery
5.1. External Configuration File
FR-CF-001: The system shall load all language-specific content and settings from an external JSON configuration file named "research_config.json" in the app's assets folder.
FR-CF-002: The configuration file shall be included in the APK during build time and accessed via Android AssetManager, ensuring availability without network connectivity or external file dependencies.
FR-CF-003: The configuration file shall contain:
•	Stroop color names and RGB values (supporting multiple languages including German, Chinese, and other character sets)
•	Task instruction texts and timing parameters
•	ASQ question texts and response scales
•	Voice recognition vocabulary and confidence thresholds
•	Network configuration parameters (team identifiers, device names)
•	Application version compatibility information
FR-CF-004: Configuration file structure:
{
  "version": "1.0",
  "network_config": {
    "team_id": "Team1",
    "device_id": "ProjectorA",
    "service_type": "_drivertest._tcp.",
    "heartbeat_interval": 5000
  },
  "stroop_colors": {
    "rot": "#FF0000",
    "blau": "#0000FF",
    "grün": "#00FF00",
    "gelb": "#FFFF00",
    "schwarz": "#000000",
    "braun": "#8B4513",
    "weiß": "#FFFFFF",
    "violett": "#800080"
  },
  "display_colors": {
    "rot": "#FF0000",
    "blau": "#0000FF",
    "grün": "#00FF00",
    "gelb": "#FFFF00",
    "schwarz": "#000000",
    "braun": "#8B4513"
  },
  "text_only_colors": ["weiß", "violett"],
  "tasks": {
    "1": {
      "label": "Navigation Setup",
      "text": "Bitte stellen Sie das Navigationsziel ein",
      "timeout_seconds": 180
    },
    "2": {
      "label": "Climate Control",
      "text": "Bitte stellen Sie die Klimaanlage auf 22°C",
      "timeout_seconds": 120
    },
    "3": {
      "label": "Radio Station",
      "text": "Bitte wechseln Sie zum Radiosender 'Bayern 3'",
      "timeout_seconds": 90
    }
  },
  "task_lists": {
    "1": {
      "label": "Basic Functions",
      "task_sequence": "1|2|3"
    },
    "2": {
      "label": "Advanced Tasks",
      "task_sequence": "3|1|2"
    }
  },
  "asq_questions": {
    "ASQ_Ease": "Die Aufgabe war einfach zu bewältigen",
    "ASQ_Time": "Ich bin mit der Zeit zufrieden, die ich benötigt habe"
  },
  "asq_scale": {
    "min_label": "Stimme überhaupt nicht zu",
    "max_label": "Stimme voll zu",
    "min_value": 1,
    "max_value": 7
  },
  "timing_defaults": {
    "stroop_display_duration": 2000,
    "min_interval": 1000,
    "max_interval": 3000,
    "countdown_duration": 4000
  },
  "voice_recognition": {
    "confidence_threshold": 0.6,
    "listening_timeout": 2000,
    "speech_timeout": 1000,
    "calibration_threshold": 0.9
  }
}
FR-CF-005: Task and Task List Structure Requirements:
FR-CF-005.1: Tasks shall contain:
•	Unique identifier (numeric, 1-99 range)
•	Short label (maximum 20 characters)
•	Full task text (paragraph format for moderator to read aloud)
•	Timeout duration in seconds (task-specific timing)
FR-CF-005.2: Task Lists shall contain:
•	Unique identifier (numeric, 1-99 range)
•	Short label (maximum 20 characters)
•	Task sequence using pipe character (|) as separator
•	Tasks executed left-to-right in sequence order
FR-CF-005.3: Task sequence format: "task_sequence": "1|3|2|5" indicates tasks 1, 3, 2, 5 in that order.
FR-CF-005.4: Pipe character (|) chosen as separator for Kotlin compatibility and JSON safety.
FR-CF-005.5: Each task timeout shall be individually configurable in seconds, allowing different complexity tasks to have appropriate time limits.
FR-CF-007: Multi-Language Stroop Support:
FR-CF-007.1: The configuration file shall support UTF-8 encoding to allow color names in multiple languages including German, Chinese (Simplified and Traditional), and other character sets.
FR-CF-007.2: Example configuration for Chinese color names:
{
  "stroop_colors": {
    "红": "#FF0000",
    "蓝": "#0000FF", 
    "绿": "#00FF00",
    "黄": "#FFFF00",
    "黑": "#000000",
    "棕": "#8B4513",
    "白": "#FFFFFF",
    "紫": "#800080"
  },
  "display_colors": {
    "红": "#FF0000",
    "蓝": "#0000FF",
    "绿": "#00FF00", 
    "黄": "#FFFF00",
    "黑": "#000000",
    "棕": "#8B4513"
  },
  "text_only_colors": ["白", "紫"]
}
FR-CF-007.3: The Projector app shall be capable of rendering and displaying Chinese characters, German umlauts, and other Unicode characters as Stroop stimuli with proper font support.
FR-CF-007.4: Chinese colleagues shall be able to edit the existing configuration file to replace German color names with Chinese equivalents without requiring separate configuration files or app modifications.
FR-CF-006: Configuration validation:
•	Apps shall validate configuration file format and completeness on startup
•	If the configuration file cannot be read, the app shall display an error message informing the user that the file is inaccessible and should not start. There are no default settings to fall back on.
•	Version compatibility checks shall ensure configuration matches app requirements
•	Task and task list references shall be validated for consistency (all referenced task IDs must exist)
5.2. Session Recovery & Crash Handling
FR-SR-001: The Master app shall store session data incrementally in JSON format to ensure data safety during app crashes.
FR-SR-002: On app startup, the system shall check if the most recent JSON file in the configured storage folder contains a session completion marker ("session_status": "completed_normally").
FR-SR-003: If an incomplete session is detected (missing completion marker), the app shall prompt the moderator with options:
•	"Continue Previous Session" button
•	"Start New Session" button
FR-SR-004: For session continuation, the system shall:
•	Identify which tasks have been completed fully
•	Display available tasks separately from completed tasks
•	Show warning dialogs when selecting completed tasks that would overwrite existing data
•	Provide progress indicators for task list sessions
FR-SR-005: The system shall not implement automatic backup strategies during sessions - session recovery relies solely on the completion marker detection and the incremental JSON saving described in FR-SR-001.
6. Implementation Phases
To ensure manageable development and testing, the implementation shall be structured in phases:
Phase 1 (MVP):
•	Basic local WiFi network connection with JSON command format
•	Simple NSD service registration and discovery with manual device selection
•	Core Stroop display functionality controlled by Master app
•	Essential task management and moderator-based data collection
Phase 2 (Enhanced):
•	Robust connection handling and automatic reconnection logic
•	Multi-device management and improved device identification
•	Advanced task management features and data export options
Phase 3 (Production):
•	Network connection quality monitoring and optimization
•	Advanced error handling and recovery mechanisms
•	Performance optimization and fine-tuning
•	Optional Google Drive integration
7. Technical Architecture and Implementation Specifications
7.1. UI Framework and Architecture
FR-ARCH-001: UI Framework Selection Both Master and Projector apps shall use traditional XML layouts instead of Jetpack Compose for better performance on older devices and mature framework stability.
FR-ARCH-002: Architecture Pattern Apps shall implement MVVM (Model-View-ViewModel) pattern with LiveData for state management and lifecycle-aware components to handle app backgrounding gracefully.
FR-ARCH-003: Navigation Framework Apps shall use Android Navigation Component with SafeArgs for type-safe navigation, providing built-in back stack management and deep linking support.
FR-ARCH-004: Dependency Injection Apps shall use Dagger/Hilt for dependency injection to ensure compile-time safety, performance optimization, and Android lifecycle handling.
7.2. Network Implementation Architecture
FR-ARCH-005: Network Library Selection Apps shall use native Android NSD APIs (NsdManager) and standard Java Socket APIs instead of third-party libraries for better control, debugging capability, and research reliability.
FR-ARCH-006: Threading Strategy Apps shall use Kotlin Coroutines with structured concurrency:
// Network operations on IO dispatcher
viewModelScope.launch(Dispatchers.IO) {
    // NSD discovery, socket connections, data transmission
}

// UI updates on Main dispatcher
withContext(Dispatchers.Main) {
    // Update connection status, device list
}

// Voice recognition on Default dispatcher
launch(Dispatchers.Default) {
    // Process audio, analyze speech results
}
FR-ARCH-007: Message Serialization Strategy
•	Phase 1: Implement JSON serialization for faster development and easier debugging
•	Phase 2: Maintain JSON for human-readable messages and debugging capabilities
•	JSON provides clear message structure during development and testing phases
FR-ARCH-008: Network Connection Timeout Values
object NetworkTimeouts {
    const val SERVICE_DISCOVERY_TIMEOUT = 30_000L // 30 seconds
    const val SOCKET_CONNECTION_TIMEOUT = 10_000L // 10 seconds
    const val DATA_TRANSMISSION_TIMEOUT = 5_000L // 5 seconds
    const val HEARTBEAT_INTERVAL = 5_000L // 5 seconds
    const val RECONNECTION_DELAY = 2_000L // 2 seconds
}
7.3. Data Collection Implementation Architecture
FR-ARCH-009: Master App Data Management The Master app shall handle all data collection locally without requiring data transmission from the Projector app:
class StroopDataCollector {
    fun recordStroopResponse(correct: Boolean, timestamp: Long) {
        val reactionTime = (timestamp - stroopDisplayTime) - MODERATOR_DELAY_CORRECTION
        stroopResponses.add(StroopResponse(correct, reactionTime))
    }
    
    companion object {
        const val MODERATOR_DELAY_CORRECTION = 500L // milliseconds
    }
}
FR-ARCH-010: Real-time Evaluation Interface The Master app shall provide immediate response collection during task execution:
// Master app evaluation interface
class TaskExecutionViewModel {
    fun onCorrectPressed() {
        recordResponse(true)
        requestNextStroop()
    }
    
    fun onIncorrectPressed() {
        recordResponse(false) 
        requestNextStroop()
    }
}
7.4. Complete Architecture Stack Summary
UI Layer: XML Layouts + Navigation Component
Presentation: MVVM with LiveData
Business Logic: Kotlin Coroutines + Use Cases
Dependency Injection: Hilt
Network Communication: Native Android NSD + TCP Sockets + JSON Commands
Data Collection: Master app real-time evaluation with moderator input
Data Storage: SharedPreferences + File System
Threading: Kotlin Coroutines with Dispatchers
This architecture stack prioritizes simplicity and reliability for the research environment while maintaining modern Android development practices. The elimination of voice recognition and data transmission complexity significantly reduces implementation risk while preserving all essential research functionality. The phased implementation approach ensures manageable development while delivering core functionality early and enhancing features incrementally.

