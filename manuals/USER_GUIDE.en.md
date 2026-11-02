# FunnyWork user guide

[简体中文](USER_GUIDE.md) | **English**

FunnyWork organizes materials, analyzes data, creates documents, and lets you follow desktop tasks from a phone. The desktop client uses files and tools within a selected workspace. The phone provides independent chat and controls for viewing, extending, and managing desktop tasks.

## Choose how to work

| Goal | Entry point | Requirements |
| --- | --- | --- |
| Ask questions or discuss ideas | **Chat** in the phone menu | Signed in with an available model and allowance; the computer may be offline |
| Process local materials, create files, or operate websites and the computer | A new desktop conversation | An open workspace, an available model, and the required tool permissions |
| Start or continue work on a computer from your phone | **Remote** in the phone menu | An online computer, the same service and account, and organization policies allowing cross-device access and remote control |
| Read synced task records and files | A remote conversation on the phone | Network access to server records; cached content is available offline |

A workspace is the computer directory used for a task. The computer and workspace selected in **Remote** determine where files are read and tools execute.

## Install and sign in

1. Get the installer for your system from the [website](https://funnywork.funnypixelsapp.com/download.en.html) or your administrator's distribution channel. On iOS, use the TestFlight invitation on the download page.
2. Install and open FunnyWork, then sign in with your username and password. For an organization deployment, use the account provided by its administrator. On the phone, configure the organization's HTTPS service address through login help.
3. Use the same service, organization, and account on phone and desktop. Check the account name after login, then open the workspace on the computer.

If login fails, check the account, password, and connection message. Contact the administrator if access is denied or the model list is empty. If installation reports an unexpected publisher or signature, verify the package with its distributor first.

## Complete your first desktop task

1. Open a workspace and create a conversation. Select an administrator-provided model and the required capability bundle.
2. Put materials in the workspace or add them through the conversation's attachment control. Identify the files in your message.
3. Describe the goal, scope, and required output. For example: “Read this month's sales spreadsheet in the workspace, compare regional revenue with targets, create a trend chart, and save an explanatory document in the workspace.”
4. After sending, follow the reply and tool activity. Review the requested action before approving it or answering a question.
5. Open the generated files and check their data, formatting, and location. Request specific changes in the same conversation.

For browser controls, install Chrome, Edge, Chromium, or Brave on the computer. Tasks use a separate visible browser window; sign in manually there when required. On macOS, grant FunnyWork Accessibility permission in System Settings; screenshots also require Screen Recording permission. Linux local previews provide browser controls. System desktop controls are available on macOS and Windows.

When a tool reports a missing browser or permission, supply the required setup and continue the task. Ordinary text chat remains available.

## Chat independently on your phone

1. Open the left menu, choose **Chat**, and create a conversation.
2. Select an available model in the input area, enter a message, and send it.
3. Read the reply. To interrupt generation, tap **Stop**, then check the conversation state.
4. Reopen a conversation from the menu to continue. Model selection applies to subsequent requests.

Phone chat accepts text. To work with computer files or execute tools, switch to **Remote** and select a desktop task.

## Connect to a computer and create a remote task

### Find an available computer

1. Open FunnyWork on the computer, sign in, and keep it connected. Confirm that the workspace is open and synchronization is enabled.
2. Open **Devices** in the phone menu. Check the computer name, connection state, and workspaces; refresh to confirm it is online.
3. If the computer is offline, check its network, account, and running client, then refresh the phone. If no workspace appears, open one on the computer first.

iOS also supports QR pairing. Choose **Connect phone** on the computer, then scan the code from **Devices → Connect device** on iOS. Check the computer name and tap **Connect**. Generate a new code on the computer when it expires. Android selects a computer on the same account from the device list.

### Create and send

1. Switch to **Remote** in the phone menu and create a conversation.
2. Select the computer and workspace, then enter the task. Choose a model or preset if those options are available and relevant.
3. Tap **Create conversation**. In the new conversation, review the transferred draft and execution location, then tap **Send**.
4. Check the task reply, processing state, or pending actions to confirm that the computer has started processing.

Creating a conversation keeps its content as a draft; sending submits the task. If creation or sending shows a pending result, use **Check result** to read the original request's state. After reconnecting, check that result before deciding whether to retry so the same work is not executed twice.

## Add materials, approve actions, and stop tasks

Add files in the desktop task's input area. On iOS, use the plus menu for Photos, Add files, or Camera; on Android, use **Add files** and the system file picker. Check attachment names and upload status, then send with text or send attachments alone. Each phone-to-computer message accepts up to 4 attachments totaling 32 MiB. Split larger materials or process them in the computer workspace.

When approval is requested, expand the request, review the action, and allow or reject it. For questions, select options or enter text and submit. Wait for the state to refresh; actions already handled on another device disappear after refresh.

You can send additional instructions while a task runs. To stop it, tap **Stop** in that desktop task's input area, wait for the receipt, and check the state. Closing the phone page leaves the computer running. If a connection loss leaves a stop result pending, reconnect and check the original action; confirm and stop it on the computer if needed.

## Find and organize conversations

- Open search in the left menu and enter title keywords. Remote search also finds message text or attachment names; open a match to read its context.
- Phone chat history supports renaming and archiving. On iOS, long-press a conversation; on Android, use the conversation item's actions.
- Open **Archived** to view or restore archived conversations. Archiving organizes the list; check execution state separately inside the computer task.
- Scroll up for older messages. When new messages arrive, use the control that returns to the latest position. Offline reading covers records saved on that device.

Message search requires a network connection and searches synced messages and attachment names. To search inside an attachment, give the file to a desktop task.

## Read results and request changes

1. Open files from the relevant desktop task. On iOS, use **Conversation files** or a result card; on Android, use **Result files** or the message's file control.
2. Check the filename, version, and synchronization state. Download and open synced files. If the computer has not uploaded them yet, keep it connected and retry.
3. View images directly. PDF report previews require the computer to generate and sync preview files. To edit further, save or share the original and open it in a compatible application.
4. To request reply or code changes, open the reply's review control: **Review results** on iOS or **Review** on Android. Enter comments and, for diffs, select files and line references. Tap **Add to draft**, review the merged content, and send it to the computer.

Review comments require your confirmation before sending from the draft. Android's **Retry preview** also adds a request to the draft first; iOS provides result reading and feedback controls. Regenerated results receive a new version, while older cards continue to show their original version.

Refresh after waiting for a preview to finish. If previewing fails, download the original first. For substituted fonts or incorrect pagination, ask the computer to regenerate the preview using installed fonts. Previously downloaded files open offline.

## Account, notifications, and storage

Open account and settings at the bottom of the menu to choose light, dark, or system appearance, view allowance, and inspect download storage. Tap the avatar to select an image, preview, and save it. Other clients display it after refreshing account information online.

On iOS, open **Notifications** in account settings to select reminders for approvals, questions, and task completion. Allow system notifications when first enabling them. Notifications require server-side Apple push configuration. If they do not arrive, check system permissions, then ask the administrator to verify the configuration. On Android, open the app, refresh in the foreground, or reconnect to see task updates.

Clearing the download cache removes local copies that can be downloaded again. Save files elsewhere first if you need them offline. Preserve required drafts and files before signing out.

## Troubleshooting

| Situation | Steps |
| --- | --- |
| Computer unavailable or device list empty | Confirm the same service and account, an online computer, and enabled synchronization; refresh the list and check organization access |
| Action result pending | Keep the original request, reconnect, and use **Check result**; pause duplicate submissions while checking |
| Queued or resources busy | Wait for the original request; ask the administrator to check model capacity if the wait continues |
| Allowance reserved | Wait for in-flight calls to settle; reserved amounts occupy available allowance until results are confirmed |
| Insufficient balance, daily limit reached, or allowance expired | Check allowance in the account page and ask the administrator to adjust limits or validity |
| Original downloads but preview fails | Save the original, check preview generation on the computer, and request regeneration if needed |
| Model configuration error | Preserve the error and task content so the administrator can check the model, credentials, and allowance |

Upgrade through the original download channel or application update control. When reporting a problem, include client version, operating system, time, steps, and sanitized screenshots. Organization users contact their administrator; public evaluation users use support on the website.

For project licensing and commercial-use conditions, see the [root licensing section](../README.en.md#license). Separately licensed portions retain their own licenses.
