package com.example.myapplication

import android.Manifest
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.View

import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import org.drinkless.td.libcore.telegram.Client
import org.drinkless.td.libcore.telegram.TdApi

import java.io.IOError
import java.io.IOException
import android.app.AlertDialog
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ConcurrentHashMap
import java.util.*

class MainActivity : AppCompatActivity() {
    private val gPermissionGranted = 10
    private var client: Client? = null
    private var authorizationState: TdApi.AuthorizationState? = null
    private var haveAuthorization = false
    private var authorizationLock = ReentrantLock()
    private var gotAuthorization = authorizationLock.newCondition()
    private var users = ConcurrentHashMap<Int, TdApi.User>()

    private var basicGroups = ConcurrentHashMap<Int, TdApi.BasicGroup>()
    private var supergroups = ConcurrentHashMap<Int, TdApi.Supergroup>()
    private var secretChats = ConcurrentHashMap<Int, TdApi.SecretChat>()

    private var chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private var mainChatList = TreeSet<OrderedChat>()
    private var haveFullMainChatList = false
    private val usersFullInfo = ConcurrentHashMap<Int, TdApi.UserFullInfo>()
    private val basicGroupsFullInfo = ConcurrentHashMap<Int, TdApi.BasicGroupFullInfo>()
    private val supergroupsFullInfo = ConcurrentHashMap<Int, TdApi.SupergroupFullInfo>()
    private val defaultHandler = DefaultHandler()

    private inner class DefaultHandler : Client.ResultHandler {
        override fun onResult(`object`: TdApi.Object) {
            alertDlg("Default handler")
            print(`object`.toString())
        }
    }

    private class OrderedChat internal constructor(
        internal val order: Long,
        internal val chatId: Long
    ) : Comparable<OrderedChat> {

        override fun compareTo(o: OrderedChat): Int {
            if (this.order != o.order) {
                return if (o.order < this.order) -1 else 1
            }
            return if (this.chatId != o.chatId) {
                if (o.chatId < this.chatId) -1 else 1
            } else 0
        }

        override fun equals(obj: Any?): Boolean {
            val o = obj as OrderedChat?
            return this.order == o!!.order && this.chatId == o.chatId
        }
    }

    private fun setChatOrder(chat: TdApi.Chat?, order: Long) {
        synchronized(mainChatList) {
                if (chat?.chatList == null || chat.chatList!!.constructor != TdApi.ChatListMain.CONSTRUCTOR) {
                    return
                }
                if (chat.order != 0L) {
                    val isRemoved = mainChatList.remove(OrderedChat(chat.order, chat.id))
                    assert(isRemoved)
                }
                chat.order = order

                if (chat.order != 0L) {
                    val isAdded = mainChatList.add(OrderedChat(chat.order, chat.id))
                    assert(isAdded)
                }
        }
    }

    private fun onAuthorizationStateUpdated(authState: TdApi.AuthorizationState) {

        this.authorizationState = authState

        when (this.authorizationState?.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                val parameters = TdApi.TdlibParameters()
                parameters.databaseDirectory = getExternalFilesDir("logs").toString() + "tdlib"
                parameters.useMessageDatabase = true
                parameters.useSecretChats = true
                parameters.apiId = 1092122
                parameters.apiHash = "50690d8227b39e579bb19985a5f564d5"
                parameters.systemLanguageCode = "en"
                parameters.deviceModel = "Samsung Note 10+"
                parameters.systemVersion = "Unknown"
                parameters.applicationVersion = "1.0"
                parameters.enableStorageOptimizer = true

                client?.send(TdApi.SetTdlibParameters(parameters), AuthorizationRequestHandler())
            }
            TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR -> client?.send(
                TdApi.CheckDatabaseEncryptionKey(),
                AuthorizationRequestHandler()
            )
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                val phoneNumber = InputCode.text.toString()
                client?.send(
                    TdApi.SetAuthenticationPhoneNumber(phoneNumber, null),
                    AuthorizationRequestHandler()
                )
            }
            TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR -> {
                val link =
                    (this.authorizationState as TdApi.AuthorizationStateWaitOtherDeviceConfirmation).link
                println("Please confirm this login link on another device: $link")
            }

            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                InputCode.setText(null);
                InputCode.setHint("Enter validation code")
                ConfirmBtn.visibility = View.VISIBLE

               /* val code = promptString("Please enter authentication code: ")
                client?.send(TdApi.CheckAuthenticationCode(code), AuthorizationRequestHandler())*/
            }

            /*TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR -> {
                val firstName = promptString("Please enter your first name: ")
                val lastName = promptString("Please enter your last name: ")
                client?.send(TdApi.RegisterUser(firstName, lastName), AuthorizationRequestHandler())
            }*/
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                alertDlg("Wait for pwd")
                /*val password = promptString("Please enter password: ")
                client.send(
                    TdApi.CheckAuthenticationPassword(password),
                    AuthorizationRequestHandler()
                )*/
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                haveAuthorization = true
                authorizationLock.lock()
                try {
                    gotAuthorization.signal()
                } finally {
                    authorizationLock.unlock()
                }
            }
            /*
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
                haveAuthorization = false
                print("Logging out")
            }
            TdApi.AuthorizationStateClosing.CONSTRUCTOR -> {
                haveAuthorization = false
                print("Closing")
            }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                print("Closed")
                if (!quiting) {
                    client = Client.create(
                        UpdatesHandler(),
                        null,
                        null
                    ) // recreate client after previous has closed
                }
            }*/
            else -> alertDlg("Unsupported authorization state:" + this.authorizationState)
        }
    }

    private inner class UpdatesHandler : Client.ResultHandler {
        override fun onResult(`object`: TdApi.Object) {
            alertDlg("Update handler")
            when (`object`.constructor) {
                TdApi.UpdateAuthorizationState.CONSTRUCTOR ->
                    onAuthorizationStateUpdated((`object` as TdApi.UpdateAuthorizationState).authorizationState)

                TdApi.UpdateUser.CONSTRUCTOR -> {
                    val updateUser = `object` as TdApi.UpdateUser
                    users[updateUser.user.id] =  updateUser.user
                }
                TdApi.UpdateUserStatus.CONSTRUCTOR -> {
                    val updateUserStatus = `object` as TdApi.UpdateUserStatus
                    val user = users[updateUserStatus.userId];
                    user?.status = updateUserStatus.status
                }
                TdApi.UpdateBasicGroup.CONSTRUCTOR -> {
                    val updateBasicGroup = `object` as TdApi.UpdateBasicGroup
                    basicGroups.put(updateBasicGroup.basicGroup.id, updateBasicGroup.basicGroup)
                }
                TdApi.UpdateSupergroup.CONSTRUCTOR -> {
                    val updateSupergroup = `object` as TdApi.UpdateSupergroup
                    supergroups.put(updateSupergroup.supergroup.id, updateSupergroup.supergroup)
                }
                TdApi.UpdateSecretChat.CONSTRUCTOR -> {
                    val updateSecretChat = `object` as TdApi.UpdateSecretChat
                    secretChats.put(updateSecretChat.secretChat.id, updateSecretChat.secretChat)
                }

                TdApi.UpdateNewChat.CONSTRUCTOR -> {
                    val updateNewChat = `object` as TdApi.UpdateNewChat
                    val chat = updateNewChat.chat
                    synchronized(chat) {
                        chats[chat.id] = chat

                        val order = chat.order
                        chat.order = 0
                        setChatOrder(chat, order)
                    }
                }
                TdApi.UpdateChatTitle.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatTitle
                    val chat = chats[updateChat.chatId]
                    chat?.title = updateChat.title
                }
                TdApi.UpdateChatPhoto.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatPhoto
                    val chat = chats[updateChat.chatId]
                    chat?.photo = updateChat.photo
                }
                TdApi.UpdateChatChatList.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatChatList
                    val chat = chats[updateChat.chatId]
                    assert(chat?.order == 0L) // guaranteed by TDLib
                    chat?.chatList = updateChat.chatList
                }
                TdApi.UpdateChatLastMessage.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatLastMessage
                    val chat = chats[updateChat.chatId]
                    chat?.lastMessage = updateChat.lastMessage
                    setChatOrder(chat, updateChat.order)
                }
                TdApi.UpdateChatOrder.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatOrder
                    val chat = chats[updateChat.chatId]
                    setChatOrder(chat, updateChat.order)
                }
                TdApi.UpdateChatIsPinned.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatIsPinned
                    val chat = chats[updateChat.chatId]
                    chat?.isPinned = updateChat.isPinned
                    setChatOrder(chat, updateChat.order)
                }
                TdApi.UpdateChatReadInbox.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatReadInbox
                    val chat = chats[updateChat.chatId]
                    chat?.lastReadInboxMessageId = updateChat.lastReadInboxMessageId
                    chat?.unreadCount = updateChat.unreadCount
                }
                /*TdApi.UpdateChatReadOutbox.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatReadOutbox
                    val chat = chats.get(updateChat.chatId)
                    synchronized(chat) {
                        chat.lastReadOutboxMessageId = updateChat.lastReadOutboxMessageId
                    }
                }
                TdApi.UpdateChatUnreadMentionCount.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatUnreadMentionCount
                    val chat = chats.get(updateChat.chatId)
                    synchronized(chat) {
                        chat.unreadMentionCount = updateChat.unreadMentionCount
                    }
                }
                TdApi.UpdateMessageMentionRead.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateMessageMentionRead
                    val chat = chats.get(updateChat.chatId)
                    synchronized(chat) {
                        chat.unreadMentionCount = updateChat.unreadMentionCount
                    }
                }
                TdApi.UpdateChatReplyMarkup.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatReplyMarkup
                    val chat = chats.get(updateChat.chatId)
                    synchronized(chat) {
                        chat.replyMarkupMessageId = updateChat.replyMarkupMessageId
                    }
                }
                TdApi.UpdateChatDraftMessage.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatDraftMessage
                    val chat = chats.get(updateChat.chatId)
                    synchronized(chat) {
                        chat.draftMessage = updateChat.draftMessage
                        setChatOrder(chat, updateChat.order)
                    }
                }
                TdApi.UpdateChatNotificationSettings.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatNotificationSettings
                    val chat = chats.get(update.chatId)
                    synchronized(chat) {
                        chat.notificationSettings = update.notificationSettings
                    }
                }
                TdApi.UpdateChatDefaultDisableNotification.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatDefaultDisableNotification
                    val chat = chats.get(update.chatId)
                    synchronized(chat) {
                        chat.defaultDisableNotification = update.defaultDisableNotification
                    }
                }
                TdApi.UpdateChatIsMarkedAsUnread.CONSTRUCTOR -> {
                    val update = `object` as TdApi.UpdateChatIsMarkedAsUnread
                    val chat = chats.get(update.chatId)
                    synchronized(chat) {
                        chat.isMarkedAsUnread = update.isMarkedAsUnread
                    }
                }
                TdApi.UpdateChatIsSponsored.CONSTRUCTOR -> {
                    val updateChat = `object` as TdApi.UpdateChatIsSponsored
                    val chat = chats.get(updateChat.chatId)
                    synchronized(chat) {
                        chat.isSponsored = updateChat.isSponsored
                        setChatOrder(chat, updateChat.order)
                    }
                }*/

                TdApi.UpdateUserFullInfo.CONSTRUCTOR -> {
                    val updateUserFullInfo = `object` as TdApi.UpdateUserFullInfo
                    usersFullInfo.put(updateUserFullInfo.userId, updateUserFullInfo.userFullInfo)
                }
                TdApi.UpdateBasicGroupFullInfo.CONSTRUCTOR -> {
                    val updateBasicGroupFullInfo = `object` as TdApi.UpdateBasicGroupFullInfo
                    basicGroupsFullInfo.put(
                        updateBasicGroupFullInfo.basicGroupId,
                        updateBasicGroupFullInfo.basicGroupFullInfo
                    )
                }
                TdApi.UpdateSupergroupFullInfo.CONSTRUCTOR -> {
                    val updateSupergroupFullInfo = `object` as TdApi.UpdateSupergroupFullInfo
                    supergroupsFullInfo.put(
                        updateSupergroupFullInfo.supergroupId,
                        updateSupergroupFullInfo.supergroupFullInfo
                    )
                }
            }// print("Unsupported update:" + newLine + object);
        }
    }


    fun alertDlg(message: String) {
        val builder = AlertDialog.Builder(this)

        builder.setMessage(message)
            .setTitle("Info")

        val dialog = builder.create()
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        }

        ConfirmBtn.visibility = View.INVISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun runLogs() {
        val err = Client.execute(
            TdApi.SetLogStream(
                TdApi.LogStreamFile(
                    getExternalFilesDir("logs").toString() + "log",
                    (1 shl 27).toLong()
                )
            ))

        if (err is TdApi.Error) {
            throw IOError(IOException("Error has occurred. " + err.message))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            gPermissionGranted -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    alertDlg("Granted. Logs will be here: " + getExternalFilesDir("logs").toString())
                } else {
                    alertDlg("GIVE ME PERMISSIONS BITCH!!")
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
        }
    }

    private fun makeRequest() {
        ActivityCompat.requestPermissions(this,
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET,
                Manifest.permission.READ_EXTERNAL_STORAGE),
            gPermissionGranted)
    }

    private fun setupPermissions() {
        val permission = ContextCompat.checkSelfPermission(this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (permission != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                val builder = AlertDialog.Builder(this)
                builder.setMessage("Permission to access files is required for this app to write logs.")
                        .setTitle("Permission required")
                            builder.setPositiveButton("OK"
                            ) { dialog, id ->
                        makeRequest()
                    }

                    val dialog = builder.create()
                dialog.show()
            } else {
                makeRequest()
            }
        }
        else {
            runLogs()
        }
    }

    fun logIn (view: View) {
        Client.execute(TdApi.SetLogVerbosityLevel( 3))
        setupPermissions()

        client = Client.create(UpdatesHandler(), null, null)

        if (client == null) alertDlg("Client null.")


        client?.send(TdApi.GetMe(), defaultHandler)
        //InfoText.setText("Login started. Please verify your account.");
    }

    fun confirm (view: View) {
         val code = InputCode.text.toString()
         client?.send(TdApi.CheckAuthenticationCode(code), AuthorizationRequestHandler())
    }

    private inner class AuthorizationRequestHandler : Client.ResultHandler {
        override fun onResult(`object`: TdApi.Object) {
            alertDlg("Authorization andler starterd")
            when (`object`.constructor) {
                TdApi.Error.CONSTRUCTOR -> {
                    System.err.println("Receive an error:")
                }
                TdApi.Ok.CONSTRUCTOR -> {
                    alertDlg("Authorized")
                }
                else -> System.err.println("Receive wrong response from TDLib")
            }// result is already received through UpdateAuthorizationState, nothing to do
        }
    }
}
