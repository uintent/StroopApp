Requirements Document: Driver
Distraction Measurement System
1. Introduction
This document outlines the requirements for a two-part
mobile
application system, "Master" and "Projector," designed to
measure
driver distraction, time on task, and task completion rates
during
specific in-car tasks.
The system will also gather user experience feedback using
a simplified
ASQ questionnaire.
The two applications will communicate wirelessly via
Bluetooth Low Energy (BLE) to
facilitate the testing process and data collection.
2. Application Overview
The system consists of two distinct applications, each with a
specific
role:
2.1. Master Application (Moderator App)
The Master app will run on a mobile device and be used by
a moderator.
Its primary functions include:
Initiating, controlling, and terminating tasks.
Managing participant and session details.
Receiving real-time data from the Projector app.
Recording moderator observations and participant
feedback.
Storing all collected data in a structured JSON format.
Providing settings for test parameters.
2.2. Projector Application (Participant App)
The Projector app will run on a separate mobile device and
display
visual stimuli to the participant.
Its primary functions include:
Displaying countdowns and Stroop test stimuli.
Utilizing voice recognition to record participant
responses to the
Stroop test.
Measuring reaction times and error rates during the
Stroop test.
Transmitting collected Stroop test data to the Master
app via
Bluetooth Low Energy.
2.3. Inter-App Communication Protocol
The Master and Projector applications will communicate
exclusively via Bluetooth Low Energy (BLE) using GATT
notifications with Protocol Buffers for optimal
performance and reliability.
Protocol Architecture:
Master app acts as GATT Central (client)
Projector app acts as GATT Peripheral (server)
advertising a custom service UUID
End-of-task data transmission using GATT characteristic
notifications
Protocol Buffers serialization for efficient data encoding
(85% size reduction vs JSON)
Multi-layer reliability with application-level
acknowledgments
Multi-Device Environment Support:
Device Identification: Each Projector app generates a
unique 8-character session identifier combined with
configurable team/researcher codes
Selective Connection: Master app displays a list of
discovered Projector devices with human-readable
names for manual selection
Custom Advertisement Data: Projector apps include
team identifier and device description in BLE
advertisement payload
Connection Verification: Master app verifies device
identity through custom GATT characteristics before
data transmission
Connection Requirements:
No device pairing required (BLE advantage over Classic
Bluetooth)
15ms connection intervals for active data transmission
MTU negotiation up to 512 bytes for batched data
transmission
Automatic reconnection with exponential backoff (1s,
2s, 4s, 8s, 16s)
RSSI monitoring and heartbeat mechanisms (5-second
intervals)
GATT Service Structure:
Protocol Buffers Message Structure:
Service UUID: "6E400001-B5A3-F393-E0A9-
E50E24DCCA9E" (research system)
├── Device Info Characteristic (Read)
│ └── Contains: Team ID, Device Name, App
Version, Capabilities
├── Command Characteristic (Write)
│ └── Master → Projector commands (start task,
stop, settings)
├── Data Characteristic (Notify)
│ └── Projector → Master task completion data
(Stroop results)
└── Status Characteristic (Read/Notify)
└── Connection status, task state, errors
Connection Flow:
1. Projector advertises with research service UUID + team
identifier
2. Master scans and displays discovered devices with
signal strength and status
3. Moderator manually selects target Projector device
4. Master connects and verifies device compatibility
5. Automatic reconnection with device identity verification
if connection drops
Data Transmission Strategy:
protobuf
message StroopMeasurement {
uint64 timestamp_ms = 1;
uint32 reaction_time_ms = 2;
bool success = 3;
float error_rate = 4;
uint32 trial_id = 5;
enum Condition { CONGRUENT = 0; INCONGRUENT = 1;
Condition condition = 6;
string session_id = 7;
}
Buffered Approach: No continuous transmission
during task execution
End-of-Task Transmission: Complete data package
sent only after task completion or timeout
Connection Resilience: Tasks continue executing even
during temporary BLE interruptions
The Master app must continuously monitor the BLE
connection and provide clear visual feedback if the
connection is lost or not established.
3. Functional Requirements
3.1. Session Management
FR-SM-001: The Master app shall allow the moderator
to input and
record the following participant details at the
beginning of a
session:
Participant Name (text)
Participant Identifier (alphanumeric, unique per
participant)
Participant Age (numeric)
Car Model (selection: "old" or "new")
FR-SM-002: The Master app shall record the exact start
time of
the interview session (after participant details are
set) into the
session's JSON data file.
FR-SM-003: The Master app shall record the exact end
time of the
interview session (when explicitly ended by the
moderator or all
tasks completed) into the session's JSON data file.
3.2. Task Management (Master App)
FR-TM-001: The Master app shall allow the moderator
to manually
select and start/stop individual pre-defined tasks.
FR-TM-002: As an alternative to manual selection, the
Master app
shall allow the moderator to select a pre-defined
"task list."
Upon selection, the app shall automatically
proceed through each
task in the list sequentially.
FR-TM-003: The Master app shall display the
instruction text and
the allowed maximum time for the current task
whenever a task is
selected or switched to.
FR-TM-003.1: Each task stored within the
application shall
include the following properties:
An alphanumeric task label (identifier).
A task instruction text.
A maximum allowed time (in seconds) after
which the task is
considered "timed out" if no other end
condition is set
by the moderator.
This information will be stored in an external
configuration
file (see FR-TM-029).
FR-TM-004: The Master app shall display a countdown
for the
allowed task completion time to the moderator
while the Projector
app is displaying stimuli.
FR-TM-004.1: While a task is running, the Master
app shall
continuously display the task time countdown,
the current task
instructions, the buttons for setting the task's
final
condition, and the "Emergency Stop" button to
the moderator.
FR-TM-005: The Master app shall allow the moderator
to set the
end condition for a task while it is active, choosing
from:
"Failed," "Success," or "Partial Success."
FR-TM-006: The Master app shall automatically set the
task end
condition to "Timed Out" if the allowed maximum
time for the
task expires before the moderator manually sets an
end condition.
FR-TM-007: The Master app shall provide a summary
screen after
each task, displaying:
The task name.
All data transferred from the Projector app for that
task.
All data recorded by the moderator for that task
(end condition,
ASQ scores if applicable).
FR-TM-008: From the task summary screen, the Master
app shall
provide the moderator with options to:
Continue to the task selection screen.
Proceed to the next task in a pre-defined task list (if
applicable).
Go back and change recorded data (task success
rating, ASQ
scores).
Restart the current task completely.
FR-TM-009: The Master app shall provide an
"Emergency Stop"
button accessible at all times, allowing the
moderator to cancel
any ongoing process (e.g., active task, data entry).
FR-TM-010: When the "Emergency Stop" button (FRTM-009) is
pressed, the Master app shall display a confirmation
prompt with
the following options for the current task's data:
Record data collected so far to JSON.
Discard current task data (remove from JSON if
partially
recorded, or prevent recording).
Return to the current process (cancel the stop
action).
FR-TM-011: During the display of the cancellation
prompt
(FR-TM-010), task timers shall continue to run, and if
the task
times out during this prompt, the task end condition
shall be
marked as "Timed Out."
FR-TM-012: If the moderator chooses to return to the
current
process from the cancellation prompt (FR-TM-010),
the task shall
resume as if no interruption occurred.
FR-TM-013: The Master app shall allow the moderator
to return to
the task selection screen or task list selection screen
at any
time.
If an active task is ongoing, this action shall trigger
the same
cancellation prompt and behavior as FR-TM010.
FR-TM-014: If the same task is completed twice for a
single
participant in the same car during the same session,
the Master
app shall, at the end of the second completion,
display a
comparative summary of both performances for that
task.
FR-TM-015: Following FR-TM-014, the Master app
shall allow the
moderator to select which of the two datasets for
the repeated
task should be retained in the JSON file, with the
other being
discarded or removed.
FR-TM-016: The Master app shall allow the moderator
to
explicitly end the overall interview process via a
dedicated
button available on the task summary screen or task
selection
screen (if task lists are not used).
FR-TM-017: The overall interview process shall also
end
automatically if all tasks in a selected task list have
been
completed.
FR-TM-018: The Master app shall provide a "Settings"
button
(e.g., a cog icon) accessible from relevant navigation
screens
(e.g., participant info entry, task selection).
Clicking this button shall open a settings menu.
FR-TM-019: The settings menu in the Master app shall
allow the
moderator to select a local storage folder for JSON
files.
FR-TM-019.1: The system shall provide a folder
picker interface allowing selection of any accessible
folder on the device's storage (internal or external).
FR-TM-019.2: The system shall validate read/write
permissions for the selected folder before
accepting the choice.
FR-TM-019.3: Selected folders must be accessible
via the device's default file manager for easy file
retrieval using USB cable or other standard
methods.
FR-TM-019.4: The system shall use
SharedPreferences to store the selected folder path
persistently across app sessions.
FR-TM-019.5: If the selected folder becomes
inaccessible (e.g., external storage removed), the
system shall prompt the user to select a new
storage location.
FR-TM-020 (Optional): Google Drive Integration
FR-TM-020.1: If automated Google Drive upload is
supported, the settings menu shall provide an
option to enable/disable automated upload.
FR-TM-020.2: The system shall use the credentials
of the currently logged-in Android user and
request permission for the app to access their
Google Drive.
FR-TM-020.3: The system shall provide a Google
Drive folder picker allowing users to select a
destination folder within their Google Drive.
FR-TM-020.4: The system shall request the
necessary authorization from the user when
Google Drive access is attempted for the first time.
FR-TM-020.5: Upon completion of an interview, if
Google Drive upload is enabled, the generated
JSON file shall be automatically uploaded to the
designated Google Drive folder while also being
saved locally.
FR-TM-021: The Master app shall provide a "Voice
Recognition System Check" option on the start screen
(after participant information has been provided and
before task selection).
FR-TM-022: The settings menu shall allow the
moderator to modify
the duration for which each Stroop is shown on the
Projector app.
The default value for this setting shall be 2 seconds.
FR-TM-023: The settings menu shall allow the
moderator to modify
the minimum time between Stroop displays on the
Projector app.
FR-TM-024: The settings menu shall allow the
moderator to modify
the maximum time between Stroop displays on the
Projector app.
FR-TM-025: The settings menu shall include an
"Apply" button.
Changes made in the settings menu shall only be
saved and
applied when this button is pressed.
FR-TM-026: The settings menu shall include a "Reset"
button.
Pressing this button shall revert all settings to their
state
before the most recent unapplied changes were
made.
FR-TM-027: The settings menu shall include a "Cancel"
button.
Pressing this button shall close the settings menu
without
saving any unapplied changes.
FR-TM-028: All settings configured by the moderator
(e.g., local
storage path, Stroop display times, Stroop intervals,
Google Drive
settings) shall be persistently stored, remaining
saved even when
the app is closed or the device is shut
down/rebooted.
FR-TM-029: The Master app shall load hard-coded
information
(e.g., task texts, allowed times, Stroop colors, ASQ
questions)
from an external configuration file in JSON format
stored in the
app's assets folder.
3.3. Android Permissions and Compatibility
FR-AP-001: Both apps shall request and handle
Android 12+ Bluetooth permissions with backward
compatibility:
FR-AP-002: API Level Requirements:
Minimum SDK: Android 8.0 (API level 26) for Pixel
2 compatibility
Target SDK: Android 14 (API level 34) for current
compliance
Compile SDK: Android 14 (API level 34)
kotlin
// Required permissions for Android 12+ (API 31+)
<uses-permission android:name="android.permission.BLU
android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLU
<uses-permission android:name="android.permission.BLU
tools:targetApi="s" /> <!-- Projector app only --
// Legacy permissions for Android 11 and below
<uses-permission android:name="android.permission.BLU
android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLU
android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACC
android:maxSdkVersion="30" />
// Audio permissions for voice recognition
<uses-permission android:name="android.permission.REC
FR-AP-003: Runtime permission handling shall
implement clear research context dialogs:
"Bluetooth access needed to connect research
devices"
"Microphone access required for German voice
recognition during Stroop tests"
"Location access required for Bluetooth device
discovery (Android 11 and below only)"
FR-AP-004: Both apps shall implement foreground
services for persistent research sessions:
Master app: ResearchSessionService
maintains BLE connection and data collection
kotlin
private fun requestBluetoothPermissions() {
when {
Build.VERSION.SDK_INT >= Build.VERSION_CODES.
requestPermissions(arrayOf(
Manifest.permission.BLUETOOTH_SCAN,
Manifest.permission.BLUETOOTH_CONNECT
Manifest.permission.RECORD_AUDIO
))
}
else -> {
requestPermissions(arrayOf(
Manifest.permission.ACCESS_FINE_LOCAT
Manifest.permission.BLUETOOTH,
Manifest.permission.BLUETOOTH_ADMIN,
Manifest.permission.RECORD_AUDIO
))
}
}
}
Projector app: StroopDisplayService ensures
uninterrupted Stroop presentation
Notifications: Low priority, ongoing notifications
explaining research purpose
Battery optimization: Request to disable battery
optimization for reliable sessions
kotlin
class ResearchSessionService : Service() {
companion object {
const val NOTIFICATION_ID = 1001
const val CHANNEL_ID = "research_session_chan
}
private fun createNotification(): Notification {
return NotificationCompat.Builder(this, CHANN
.setContentTitle("Research Session Active
.setContentText("Driver distraction measu
.setSmallIcon(R.drawable.ic_research)
.setOngoing(true)
.setPriority(NotificationCompat.PRIORITY_
.build()
}
}
FR-AP-005: Apps shall use Companion Device
Manager API to streamline BLE connection without
requiring location permissions on Android 12+.
FR-AP-006: Apps shall verify device capabilities on
startup:
Bluetooth LE support
Microphone availability
Speech recognition service availability
Display error messages for missing capabilities
FR-BLE-001: The Projector app shall act as a BLE GATT
Peripheral (server) and advertise a custom service with
UUID "0000180F-0000-1000-8000-00805F9B34FB".
FR-BLE-002: The Master app shall act as a BLE GATT
Central (client) and scan for Projector devices
advertising the custom service UUID.
FR-BLE-003: The Projector app shall include the
following information in its BLE advertisement payload:
Team identifier (configurable)
Device description/name
App version
FR-BLE-004: The Master app shall display a list of
discovered Projector devices with human-readable
names for manual selection by the moderator.
FR-BLE-005: The Master app shall implement the
following GATT characteristics:
Device Info Characteristic (Read): Contains Team
ID, Device Name, App Version
Command Characteristic (Write): For Master →
Projector commands (start task, stop, settings)
Data Characteristic (Notify): For Projector →
Master real-time data (Stroop results)
Status Characteristic (Read/Notify): Connection
status, task state, errors
FR-BLE-006: The system shall use Protocol Buffers for
data serialization to achieve efficient data encoding
with up to 85% size reduction compared to JSON.
FR-BLE-007: The Master app shall implement
automatic reconnection with exponential backoff (1s,
2s, 4s, 8s, 16s) if the BLE connection is lost.
FR-BLE-008: The system shall negotiate MTU up to 512
bytes for batched data transmission and use 15ms
connection intervals for real-time data transmission.
FR-BLE-009: The Master app shall verify device identity
through custom GATT characteristics before initiating
data transmission.
FR-BLE-010: No device pairing shall be required for
BLE connection establishment.
3.4. BLE Communication (Master and Projector
Apps)
FR-ST-001: The Projector app shall display a
countdown (3, 2,
1, 0) for four seconds before the Stroop test begins.
No other content shall be displayed during this
countdown.
FR-ST-002: After the countdown (FR-ST-001) finishes
(at 0), the
Projector app shall begin displaying Stroop stimuli.
FR-ST-003: The Projector app shall display Stroop
stimuli for a
duration configurable on the Master app (see FRTM-022).
FR-ST-004: The Projector app shall display Stroop
stimuli at
semi-random intervals, where the minimum and
maximum interval
times are configurable on the Master app (see FRTM-023,
FR-TM-024).
The first Stroop after the initial countdown, and all
subsequent
Stroops, shall appear after a value selected
uniformly at
random between the specified minimum and
maximum interval
times, ensuring unpredictability for the
participant.
FR-ST-005: The Projector app shall utilize voice
recognition (in
German, suitable for a quiet environment) to detect
participant
responses to the displayed Stroop word.
A Stroop shall be counted as 'failed' if:
a) The participant names a color incorrectly (i.e.,
a
recognized color from the hard-coded list
that is not the
color of the current Stroop).
Upon an incorrect color being named, the
Stroop shall
immediately disappear from the screen
and be recorded
as failed.
b) The correct color name is not named while
the Stroop is
displayed on screen (i.e., before the Stroop's
display
duration has elapsed).
If the voice input is recognized but is not a
color name
from the hard-coded list, it shall be ignored,
allowing
the participant to continue trying to name
the correct
color as long as the Stroop is displayed.
FR-ST-006: The Projector app shall contain a hardcoded list of
permissible German Stroop colors (color names as
strings: "rot," "blau," "grün," "gelb," "schwarz,"
"braun," "weiß," "violett") and
corresponding actual colors, loaded from an external
configuration
file (see FR-TM-029).
FR-ST-006.1: The colors white ("weiß") and violet
("violett") shall never be displayed as visual colors
due to background conflicts and potential
confusion.
FR-ST-006.2: The words "weiß" and "violett" may
appear as text stimuli but will be displayed in other
colors from the available palette.
FR-ST-007: The Projector app shall never display the
color name
in its corresponding actual color (e.g., the word
"RED" will
never be displayed in red ink).
FR-ST-008: Projector App Visual Requirements
FR-ST-008.1: The Stroop stimuli shall only be
displayed on the Projector app, not the Master app.
FR-ST-008.2: Between tasks, the Projector app
shall display a black screen.
FR-ST-008.3: The initial countdown (3, 2, 1, 0) shall
be displayed on a black background.
FR-ST-008.4: Stroop color words shall be displayed
on a white background.
FR-ST-008.5: A small cog icon for settings access
shall be continuously visible in the lower-right
corner of the landscape screen.
FR-ST-008.6: No participant instructions shall be
displayed on the Projector app.
FR-ST-008.7: Only the following elements shall be
visible: settings cog icon, countdown numbers, and
Stroop stimuli.
FR-ST-009: When the 'Voice Recognition System
Check' option
(FR-TM-021) is activated on the Master app, the
Projector app
shall initiate a process to verify its voice recognition
system
is properly configured and functional.
FR-ST-010: The number of Stroops shown per task
shall be
dynamically determined by the task's allowed time,
the individual
Stroop display duration, and the semi-random
intervals between
Stroops.
The minimum number of Stroops shown per task
shall be 0.
FR-ST-011: Voice Recognition Calibration Process
(Projector App)
The Projector app shall, upon activation of the
"Calibrate
Voice Recognition" option (FR-TM-021), initiate
a dedicated
calibration process to optimize the voice
recognition system
for the individual participant's voice and the
specific
Stroop test vocabulary.^1^
This process shall include:
FR-ST-011.1: Microphone Readiness
Feedback: Providing
visual feedback (e.g., a pulsating icon or a
dynamic bar
graph) that responds to the microphone's
input levels
(onRmsChanged callback) to assist the
participant in
adjusting their speaking volume for optimal
audio
quality.^2^
FR-ST-011.2: Clear Speaking Instructions:
Displaying
explicit, on-screen instructions in German,
guiding the
participant to speak clearly and at a
moderate pace,
enunciating the German color names.^5^
FR-ST-011.3: Voice Sample Collection for
Adaptation:
Prompting the participant to verbally utter
each of the
hard-coded German color names (FR-ST006) ^1^ during this
phase. These samples shall be used to
facilitate the voice
recognition engine's adaptation to the
participant's
pronunciation and to bias the recognition
model towards
the target vocabulary.
FR-ST-012: Hybrid Voice Recognition Strategy
(Projector App)
FR-ST-012.1: Primary On-Device Recognition:
The Projector app shall use native Android
SpeechRecognizer as the first-line voice recognition
system during Stroop testing for low latency
performance.
FR-ST-012.2: Decision Logic for Cloud
Handover: When on-device recognition cannot
determine with sufficient confidence whether the
response is clearly correct or clearly incorrect, the
system shall:
Immediately end the current Stroop display
Hand over the audio sample to Google Cloud
Speech-to-Text for re-evaluation
Mark the Stroop result based on cloud
recognition outcome
Preserve the original speech start timestamp
for accurate reaction time measurement
FR-ST-012.3: Cloud Processing Behavior: During
cloud handover:
Stroop stimulus shall disappear from screen
immediately upon handover decision
Participant shall not be aware of the dualprocessing approach
System shall maintain speech timing accuracy
regardless of processing method
FR-ST-012.4: Confidence Threshold
Management:
Clear correct recognition: confidence ≥ 0.8 with
matching German color name
Clear incorrect recognition: confidence ≥ 0.8
with non-matching color name
Uncertain recognition: confidence < 0.8 or
ambiguous results trigger cloud handover
FR-ST-012.5: Global German Language
Optimization: The system shall configure Google
Cloud Speech-to-Text with German language
model and PhraseSet objects containing German
color names with boost values (2.0-5.0 range) to
improve recognition accuracy for all participants.
FR-ST-012.6: Complete Recognition Failure:
When both on-device and cloud recognition fail to
produce any valid result, the Stroop shall be
marked as failed and recorded as an incorrect
response.
FR-ST-013: Offline Language Pack Management
(Projector App)
The Projector app shall programmatically check for
the presence
of the German offline speech recognition
language pack on the
device. If not installed, the app shall prompt the
participant
to download it via system settings to ensure
consistent and
reliable offline performance.^5^
3.6. Data Collection & Transfer (Projector to Master)
FR-DCT-001: The Projector app shall measure and
transmit the
following data points to the Master app for each
task:
Error rate in Stroop color recognition (percentage
of correctly
named Stroops).
Error rate in Stroop color recognition (percentage
of
incorrectly named Stroops, including those
where no valid
response or an unrecognizable/incorrect color
name was given).
Total number of Stroops shown.
Average reaction time for all Stroops.
Individual reaction times for each Stroop
presented.
FR-DCT-002: Data Transmission Timing Strategy:
FR-DCT-002.1: The Projector app shall NOT
transmit Stroop data continuously during task
execution.
FR-DCT-002.2: All Stroop measurement data shall
be buffered locally on the Projector device during
task execution.
FR-DCT-002.3: Complete data transmission shall
occur only after:
The Projector receives task end signal from
Master app, OR
Task timeout occurs as determined by local
task timer
FR-DCT-002.4: This approach ensures task
continuity even during temporary Bluetooth
connection interruptions.
FR-DCT-003: The reaction time for an individual Stroop
shall be
measured from the moment the Stroop stimulus
appears on screen
until the start of the participant's utterance of the
correct
color name.
The system shall record the start time of the speech
and
validate the correctness of the utterance while
the task timer
continues running.
FR-DCT-004: If a task is completed before any Stroops
are shown
(i.e., 0 Stroops displayed), the Projector app shall
transmit a
Stroop completion rate of 100% and an average
reaction time of 0
seconds for that task.
3.7. User Feedback (ASQ)
FR-UF-001: After a task is ended with the "Success"
end
condition (FR-TM-005), the Master app shall present
two specific
German ASQ questions to the moderator.
FR-UF-001.1: Each ASQ question shall have a
unique
identifier: "ASQ_Ease" and "ASQ_Time".
The exact German wording for these questions
will be loaded from an
external configuration file (see FR-TM-029).
FR-UF-002: For each question in FR-UF-001, the
Master app shall
provide a 7-point scale (1 to 7) ranging from
"Stimme überhaupt nicht zu" to "Stimme voll zu"
(German equivalents of "Don't agree at all" to
"Agree completely").
FR-UF-003: The Master app shall allow the moderator
to input the
participant's stated numerical answer (1-7) for each
ASQ
question.
FR-UF-004: The ASQ questions and scales shall be
hard-coded
within the Master app and can be displayed on
subsequent screens
if necessary.
FR-UF-005: The ASQ shall only be presented and
recorded if the
task's end condition is explicitly set to "Success" by
the
moderator.
FR-UF-006: If the ASQ is not recorded due to the task
ending
with a condition other than "Success" (e.g., "Failed,"
"Partial Success," "Timed Out"), the Master app shall
record
"NA" for the ASQ data fields for that task in the
JSON file.
3.8. Data Persistence & Export
FR-DP-001: All collected data for a participant's
session shall
be stored in a single JSON file.
FR-DP-002: The JSON file for each participant shall
contain:
All participant details (name, identifier, age, car
model).
Interview start time.
Interview end time.
For each task completed:
Task identifier (corresponding task label).
Time required for task completion (from
countdown end to
task end).
End condition ("Failed," "Success," "Partial
Success,"
"Timed Out").
Error rate in Stroop color recognition
(percentage correct).
Error rate in Stroop color recognition
(percentage
incorrect).
Total number of Stroops shown.
Average reaction time for all Stroops.
Individual reaction times for each Stroop.
Recorded ASQ scores (if applicable, otherwise
"NA").
Task end time.
FR-DP-003: The JSON file shall be named using a
specific format for clear participant identification:
Format: [CarType][ParticipantName]
[ParticipantAge][Date]_[Time].json
CarType: "old" or "new" (as selected during
participant setup)
ParticipantName: Participant name with spaces
removed and special characters sanitized
ParticipantAge: Participant age as entered
(numeric)
Date: Current date in German format
DD.MM.YYYY
Time: Current time in 24-hour format HH:MM
Examples:
old_JohnDoe35_24.06.2025_14:30.json
new_MariaSchmidt42_24.06.2025_09:15.json
FR-DP-003.1: File naming validation and
sanitization:
Participant names shall have umlauts converted
(ä→ae, ö→oe, ü→ue, ß→ss)
Special characters shall be removed or replaced
with underscores
Duplicate filenames shall append sequential
numbers: _001, _002, etc.
Maximum filename length shall be validated
for filesystem compatibility
FR-DP-004: File Management and Access:
FR-DP-004.1: JSON files shall be stored in userselected folders accessible via the device's default
file manager.
FR-DP-004.2: File access for moderators shall be
provided through standard methods (USB cable
connection, file manager access) without requiring
special app functionality.
FR-DP-004.3: The system shall not implement
automatic backup strategies - session recovery
shall rely solely on the session completion marker
detection described in FR-SR-002.
FR-DP-005 (Optional): Google Drive Upload
FR-DP-005.1: Upon completion of an interview, if
Google Drive integration is enabled, the generated
JSON file shall be automatically uploaded to the
designated Google Drive folder.
FR-DP-005.2: Google Drive upload shall occur in
addition to local file storage, not as a replacement.
FR-DP-005.3: Upload failures shall not prevent
local file storage or affect session completion.
3.9. Process Control & Timing
FR-PC-001: The countdown displayed on the Projector
app
(FR-ST-001) shall not count towards the task
completion time.
FR-PC-002: Task timers for both the Projector (Stroop
display)
and Master (task duration and recorded time on task
counter) shall
commence only after the countdown on the
Projector app has
completed (reached "0").
FR-PC-003: If the moderator chooses to cancel a task
(via
FR-TM-010) and decides to record the data so far,
the system shall
record the task as it stood at the moment of
cancellation,
including the time taken until that point.
FR-PC-004: Precise Utterance Start Detection
For accurate reaction time measurement (FR-DCT003) ^1^
, the
Projector app shall precisely identify the start of
the participant's utterance using the
onBeginningOfSpeech callback from the
RecognitionListener.^13^ This timestamp shall
be recorded as the beginning of the vocal
response.
4. Non-Functional Requirements
4.1. Performance
NFR-PER-001: BLE communication between Master
and
Projector apps shall be responsive, ensuring realtime data
transfer for Stroop measurements without
noticeable delay.
NFR-PER-002: Voice recognition on the Projector app
shall be
performed efficiently to accurately capture
participant responses
and measure reaction times.
NFR-PER-003: Voice Recognition Accuracy for
Stroop Colors
The voice recognition system shall achieve a high
recognition
accuracy (e.g., >95%) for the hard-coded
German color names
(FR-ST-006) ^1^ for all participants, given a
quiet testing
environment and the specific vocabulary. This
accuracy shall
be consistent across Pixel 2 and Pixel 5+
devices.^14^
4.2. Usability & UI
NFR-US-001: The Projector app's display of the
countdown and
Stroop stimuli shall prioritize visibility through large
font sizes and operate in landscape orientation.
NFR-US-002: The Master app shall operate in portrait
orientation for optimal moderator usability.
NFR-US-003: The Projector app shall visually
differentiate the
countdown from the Stroop stimuli:
Countdown: white numbers on black
background
Stroops: German color names in different colors
on white background
Between tasks: black screen display
NFR-US-004: All German text elements (color names,
ASQ questions, instructions) shall use appropriate
German typography and character encoding (UTF-8).
NFR-US-005: Navigation and UI layout requirements:
Critical action buttons shall be displayed in a
sticky navigation area at the bottom of the
screen
Navigation area shall remain visible at all times
Users shall not be required to use Android
system back button for navigation
Swipe gestures for scrolling shall be enabled for
vertical navigation
4.3. Reliability & Robustness
NFR-REL-001: The Master app shall continuously
monitor the
BLE connection to the Projector app and provide
clear visual
feedback and prompts to the moderator if the
connection is lost or
not established.
NFR-REL-002: The system shall handle scenarios where
voice
recognition fails to detect a valid response or
detects irrelevant
speech, influencing the "incorrectly named" count.
NFR-REL-003: Data saving to JSON shall be robust,
preventing
data loss in unexpected application closures or
errors.
NFR-REL-004: Handling of Non-Stroop Utterances
If the voice recognition system detects speech that
is not a
valid German color name from the hard-coded
list (FR-ST-006)
^1^
, it shall ignore this input, allowing the
participant to
continue attempting to name the correct color
as long as the
Stroop stimulus is displayed (FR-ST-005b).^1^
4.4. Compatibility
NFR-COMP-001: The applications shall initially target
implementation and full functionality on Pixel 2
devices.
NFR-COMP-002: If implementation on Pixel 2 devices
is not
feasible, the target devices shall be upgraded to
Pixel 5 or
higher.
NFR-COMP-003: Cross-Device Performance
Consistency
The overall performance of the voice recognition
system,
including its accuracy and responsiveness, shall
be consistent
and reliable when deployed on both Pixel 2 and
Pixel 5+
devices.^14^
5. Configuration Requirements & Session
Recovery
5.1. External Configuration File
FR-CF-001: The system shall load all language-specific
content from an external JSON configuration file
named "research_config.json" that is packaged with the
application in the assets folder.
FR-CF-002: The configuration file shall be included in
the APK during build time and accessed via Android
AssetManager, ensuring availability without network
connectivity or external file dependencies.
FR-CF-003: The configuration file shall contain:
German color names and their RGB values for
Stroop testing
German task instruction texts
German ASQ question texts and response scales
Default timing parameters (Stroop display duration,
intervals)
Voice recognition vocabulary lists and confidence
thresholds
Application version compatibility information
FR-CF-004: Configuration file structure:
json
{
"version": "1.0",
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
"text": "Bitte stellen Sie das Navigationsziel
"timeout_seconds": 180
},
"2": {
"label": "Climate Control",
"text": "Bitte stellen Sie die Klimaanlage auf
"timeout_seconds": 120
},
"3": {
"label": "Radio Station",
"text": "Bitte wechseln Sie zum Radiosender 'Ba
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
"ASQ_Ease": "Die Aufgabe war einfach zu bewältige
"ASQ_Time": "Ich bin mit der Zeit zufrieden, die
},
"asq_scale": {
"min_label": "Stimme überhaupt nicht zu",
"max_label": "Stimme voll zu",
"min_value": 1,
"max_value": 7
FR-CF-005: Task and Task List Structure Requirements:
FR-CF-005.1: Tasks shall contain:
Unique identifier (numeric, 1-99 range)
Short label (maximum 20 characters)
Full task text (paragraph format for moderator
to read aloud)
Timeout duration in seconds (task-specific
timing)
FR-CF-005.2: Task Lists shall contain:
Unique identifier (numeric, 1-99 range)
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
Short label (maximum 20 characters)
Task sequence using pipe character (|) as
separator
Tasks executed left-to-right in sequence order
FR-CF-005.3: Task sequence format:
"task_sequence": "1|3|2|5" indicates tasks 1, 3, 2, 5
in that order.
FR-CF-005.4: Pipe character (|) chosen as separator
for Kotlin compatibility and JSON safety.
FR-CF-005.5: Each task timeout shall be
individually configurable in seconds, allowing
different complexity tasks to have appropriate time
limits.
FR-CF-006: Configuration validation:
Apps shall validate configuration file format and
completeness on startup
If the configuration file cannot be read, the app
shall display an error message informing the user
that the file is inaccessible and should not start.
There are no default settings to fall back on.
Version compatibility checks shall ensure
configuration matches app requirements
Task and task list references shall be validated for
consistency (all referenced task IDs must exist)
5.2. Session Recovery & Crash Handling
FR-SR-001: The Master app shall store session data
incrementally in JSON format to ensure data safety
during app crashes.
FR-SR-002: On app startup, the system shall check if
the most recent JSON file in the configured storage
folder contains a session completion marker
("session_status": "completed_normally").
FR-SR-003: If an incomplete session is detected
(missing completion marker), the app shall prompt the
moderator with options:
"Continue Previous Session" button
"Start New Session" button
FR-SR-004: The system shall not implement automatic
backup strategies during sessions - session recovery
relies solely on the completion marker detection and
the incremental JSON saving described in FR-SR-001.
FR-SR-004: For session continuation, the system shall:
Identify which tasks have been completed fully
Display available tasks separately from completed
tasks
Show warning dialogs when selecting completed
tasks that would overwrite existing data
Provide progress indicators for task list sessions
6. Implementation Phases
To ensure manageable development and testing, the
implementation shall be structured in phases:
Phase 1 (MVP):
Basic BLE connection with JSON message format
Simple command/response pattern between Master
and Projector
Manual device selection for BLE pairing
Core Stroop test functionality with basic voice
recognition
Essential task management and data collection
Phase 2 (Enhanced):
Protocol Buffers implementation for data serialization
Advanced BLE reconnection logic with exponential
backoff
Voice recognition calibration features
Multi-device management and improved device
identification
Phase 3 (Production):
BLE connection quality monitoring and optimization
Advanced error handling and recovery mechanisms
Performance optimization and fine-tuning
Optional Google Drive integration
7. Open Questions & Clarifications
The following clarifications have been addressed:
Bluetooth Protocol: BLE with GATT notifications and
research-specific service UUID selected
Data Format: Protocol Buffers for efficiency with
detailed message structure defined
Device Pairing: Manual selection from discovered
devices with team identification
Configuration File: JSON format with comprehensive
German language content in app assets
File Naming: German date format with participant
identification for uniqueness
Voice Recognition Strategy: Hybrid approach with
clear confidence thresholds specified
Data Transmission: Buffered end-of-task approach for
connection resilience
9. Technical Architecture and Implementation
Specifications
9.1. UI Framework and Architecture
FR-ARCH-001: UI Framework Selection
Both Master and Projector apps shall use
traditional XML layouts instead of Jetpack
Compose
Rationale: Better performance on older devices
(Pixel 2 compatibility), mature and stable
framework, extensive documentation support
FR-ARCH-002: Architecture Pattern
Apps shall implement MVVM (Model-ViewViewModel) pattern with LiveData for state
management
Separation of concerns:
Lifecycle-aware components to handle app
backgrounding gracefully
FR-ARCH-003: Navigation Framework
Apps shall use Android Navigation Component
with SafeArgs for type-safe navigation
Benefits: Built-in back stack management, deep
linking support, visual navigation graph
FR-ARCH-004: Dependency Injection
Apps shall use Dagger/Hilt for dependency
injection
Compile-time safety, performance optimization,
Android lifecycle handling
9.2. BLE Implementation Architecture
FR-ARCH-005: BLE Library Selection
Apps shall use native Android BLE APIs
(BluetoothAdapter, BluetoothGatt) instead of thirdparty libraries
kotlin
SessionViewModel -> manages participant data,
BluetoothViewModel -> handles BLE communicatio
VoiceRecognitionViewModel -> manages speech re
Direct API usage for better control, debugging
capability, and research reliability
FR-ARCH-006: Threading Strategy
Apps shall use Kotlin Coroutines with structured
concurrency:
FR-ARCH-007: Message Serialization Strategy
Phase 1: Implement JSON serialization for faster
development and easier debugging
kotlin
// BLE operations on IO dispatcher
viewModelScope.launch(Dispatchers.IO) {
// BLE scanning, connection, data transmis
}
// UI updates on Main dispatcher
withContext(Dispatchers.Main) {
// Update connection status, device list
}
// Voice recognition on Default dispatcher
launch(Dispatchers.Default) {
// Process audio, analyze speech results
}
Phase 2: Migrate to Protocol Buffers for efficiency
optimization
JSON provides human-readable messages during
development phase
FR-ARCH-008: BLE Connection Timeout Values
9.3. Voice Recognition Implementation Architecture
FR-ARCH-009: Google Cloud API Authentication
Use Service Account authentication with JSON key
stored in app assets
No user authentication required for predictable
billing and offline-first operation
kotlin
object BleTimeouts {
const val SCAN_TIMEOUT = 30_000L // 30
const val CONNECTION_TIMEOUT = 10_000L // 1
const val DATA_TRANSMISSION = 5_000L // 5
const val HEARTBEAT_INTERVAL = 5_000L // 5
const val RECONNECTION_DELAY = 2_000L // 2
}
FR-ARCH-010: Audio File Handling Strategy
Use temporary in-memory storage (ByteArray) for
audio samples
No file system storage to avoid permissions
complexity and ensure privacy
FR-ARCH-011: Recognition Engine Switching Logic
kotlin
class CloudSpeechManager {
private fun initializeCredentials() {
val credentialsStream = assets.open("serv
val credentials = ServiceAccountCredentia
}
}
kotlin
class VoiceRecognitionManager {
private var audioBuffer: ByteArray? = null
fun processWithCloud(audioData: ByteArray) {
// Send directly to Cloud Speech API
audioBuffer = null // Clear after process
}
}
Implement timeout-based handoff between
Android and Cloud recognition
9.4. Complete Architecture Stack Summary
kotlin
class HybridRecognitionManager {
fun startRecognition() {
val localResult = withTimeout(500) { // 5
androidSpeechRecognizer.recognize()
}
if (localResult.confidence < 0.8) {
val cloudResult = withTimeout(2000) {
cloudSpeechApi.recognize(audioBuff
}
return cloudResult
}
return localResult
}
}
This architecture stack prioritizes reliability and simplicity
for the research environment while maintaining modern
Android development practices.
This document provides a comprehensive outline for the
development of
the Master and Projector applications with detailed BLE
communication
specifications.
The phased implementation approach ensures manageable
development while
UI Layer: XML Layouts + Navigation
Component
Presentation: MVVM with LiveData
Business Logic: Kotlin Coroutines + Use
Cases
Dependency Injection: Hilt
BLE Communication: Native Android APIs + JSON
Voice Recognition: Android SpeechRecognizer +
Google Cloud
Data Storage: SharedPreferences + File
System
Threading: Kotlin Coroutines with
Dispatchers
delivering core functionality early and enhancing features
incrementally.
Works cited
1. accessed January 1, 1970,
2. Real-Time Speech Transcription on Android with
SpeechRecognizer -
WebRTC.ventures, accessed June 24, 2025,
[https://webrtc.ventures/2025/03/real-time-speechtranscription-on-android-with-speechrecognizer/]
{.underline}
3. Discover the core Android API for Speech Recognition.
- DEV
Community, accessed June 24, 2025,
[https://dev.to/charfaouiyounes/discover-the-coreandroid-api-for-speech-recognition-99n]{.underline}
4. Speech to Text | Android Studio | Kotlin - YouTube,
accessed June
24, 2025, [https://www.youtube.com/watch?
v=yNCBWmSSV4Y]{.underline}
5. How to Turn On Voice to Text on Android [2025 Guide]
- Maestra,
accessed June 24, 2025,
[https://maestra.ai/blogs/how-to-turn-on-voice-to-
text-on-android]{.underline}
6. Talk-to-Text on Android: Improve Your Productivity with
Speech
