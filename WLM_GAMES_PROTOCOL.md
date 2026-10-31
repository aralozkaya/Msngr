# Windows Live Messenger Games & Activities (P4) Protocol Specification

Reverse-engineered from `C:\Program Files (x86)\Windows Live\Messenger\msnmsgr.exe` (version `14.0.8117.416`, patched for Escargot), its embedded **`P4PLib`** TypeLib (`msnmsgr.exe\6`), and the live Escargot App Directory endpoints (`conf.escargot.chat` and `apps.escargot.nina.chat`).

Internally in `msnmsgr.exe`, the Games and Shared Activities subsystem is named **P4**, implemented across:
- `CConfigServerClient` / `CSGetAppDirectoryInfo` / `CSGetAppEntryInfo` (`0x005b2460`–`0x005b2980`)
- `CLocalAppDirectory` & `CAppPopupMenu` (`0x0058c850`–`0x0058fc90`)
- `CP4Channel` (`0x005d6b7c`–`0x005da8a0`)
- `CP4External` (`0x005dad1b`–`0x005dbb6b`)
- `CP4Users` & `CP4User` (`0x005dbc20`–`0x005dc350`)
- `CP4AppEntry` (`0x005dc36c`–`0x005dc9d0`)
- `CGameBrowser` (`0x005dcd62`–`0x005de1cf`)
- `CP4EUF` (`0x005de296`–`0x005e0a30`)
- `CP4Data` (`0x005e0b80`–`0x005e1dff`)
- `CP4Slave` (`0x005e1e48`–`0x005e25f0`)

---

## 1. Config Server & Game Catalog Discovery

### 1.1 Discovering the App Directory Endpoints (`MsgrConfig.asmx`)
At sign-in, `CSMsgrGetConfigInfo` (`0x005b2460`) queries the Messenger Config Server (`0x0043e888`):
```http
GET http://conf.escargot.chat/Config/MsgrConfig.asmx?op=GetClientConfig&Country=US&CLCID=0409&PLCID=0409&GeoID=244&ver=14.0.8117.416
```
Inside `<MsgrConfig><LocalizedConfig Market="en-US"><AppDirConfig>`, WLM reads three URLs:
```xml
<AppDirConfig>
  <AppDirPageURL>http://apps.escargot.nina.chat/activities/directory/</AppDirPageURL>
  <AppDirSeviceURL>http://apps.escargot.nina.chat/activities/service/</AppDirSeviceURL>
  <AppDirVersionURL>http://apps.escargot.nina.chat/activities/version/</AppDirVersionURL>
</AppDirConfig>
```
On CrossTalk (`conf.msgrsvcs.ctsrv.gay`), `<AppDirConfig>` resolves to:
```xml
<AppDirConfig>
  <AppDirPageURL>http://mactivities.msgrsvcs.ctsrv.gay/AppDirectory/Directory.aspx?L=en-us</AppDirPageURL>
  <AppDirSeviceURL>http://mactivities.msgrsvcs.ctsrv.gay/AppDirectory/AppDirectory.asmx</AppDirSeviceURL>
  <AppDirVersionURL>http://mactivities.msgrsvcs.ctsrv.gay/AppDirectory/GetAppdirVersion.aspx</AppDirVersionURL>
</AppDirConfig>
```
> **Note**: `<AppDirSeviceURL>` (missing the `r` in `Service`) is an original Microsoft spelling typo hardcoded at VA `0x0043e480`.

### 1.2 Fetching the Games / Activities Menu Catalog (`GetFilteredDataSet2`)
When the user clicks the **Games** (`AppType = 0`) or **Activities** (`AppType = 1`) toolbar button in a conversation window, `CAppPopupMenu` (`0x0058ddac`) queries `CLocalAppDirectory` (`0x0058e2b5`), which calls `CSGetAppDirectoryInfo` (`0x005b2660`) with the format string at `0x0043e920`:
```http
GET http://apps.escargot.nina.chat/activities/service/?op=GetFilteredDataSet2&locale=en-US&Page=1&Kids=-1&AppType=0&ver=14.0.8117.416
```
- `locale`: IETF language tag converted from the 4-digit hex LCID via `GetLocaleInfoW` (e.g., `0409` -> `en-US`).
- `Page`: `1`.
- `Kids`: `-1` for standard accounts, `1` for kid-restricted accounts.
- `AppType`:
  - `0` = **Games** (cached locally under config cache ID `2`)
  - `1` = **Activities** (cached locally under config cache ID `3`)
- `ver`: Client version string (e.g., `14.0.8117.416`).

### 1.3 `<AppDir>` Catalog XML Schema & Menu Parsing
`CLocalAppDirectory::ParseAppDirXml` (`0x0058fc31`) parses two top-level XML node lists under `<AppDir>`:

1. **`<Category>` (`0x0058f973`)**:
   ```xml
   <Category>
     <CategoryID>101</CategoryID>
     <Locale>en-us</Locale>
     <Sequence>1</Sequence>
     <Name>Microsoft</Name>
     <Description>Official games by Microsoft and Zone.com</Description>
   </Category>
   ```
2. **`<Entry>` (`0x0058fad2`)**:
   ```xml
   <Entry>
     <EntryID>101000</EntryID>
     <Locale>en-us</Locale>
     <Sequence>1</Sequence>
     <CategoryID>101</CategoryID>
     <Name>Tic Tac Toe</Name>
     <Description>Play classic Tic Tac Toe with a friend!</Description>
     <URL>http://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/app-data/tic-tac-toe/tictactoe.htm</URL>
     <AppIconURL/>
     <Type>game</Type>
     <Height>500</Height>
     <Width>390</Width>
     <Location>side</Location>
     <MinUsers>2</MinUsers>
     <MaxUsers>2</MaxUsers>
     <Kids>1</Kids>
     <AppType>0</AppType>
   </Entry>
   ```

#### Menu Construction & Badges (`CLocalAppDirectory` & `CAppPopupMenu`)
- `CLocalAppDirectory::MatchEntriesToCategories` (`0x0058f76b`) attaches each `<Entry>` to its parent `<Category>` by matching `<CategoryID>`, then sorts both categories and entries in ascending order by `<Sequence>` (`0x0058f0b1`).
- **Icons**: `CLocalAppDirectory::DownloadIcon` (`0x0058ea75`) downloads `<AppIconURL>` via `URLDownloadToCacheFileW`. If `<AppIconURL>` is empty or fails to load, `CAppPopupMenu` falls back to WLM's built-in resource icon ID `0x14e` (`334`).
- **Player-Count Badges** (`CAppPopupMenu::AddMenuItem` at `0x0058de4b`):
  - If `MinUsers == 1` (`0x0058e003`), displays the `SinglePlayer` icon badge (`0x0043866c`).
  - If `MaxUsers == 2` (`0x0058e033`), displays the `TwoPlayer` icon badge (`0x00438658`).
- Clicking a menu item dispatches command ID `40100 + index` (`0x9ca4` at `0x0058ddca`), which calls `CP4Data::StartAppById(EntryID)` (`0x0058d354`).

#### Live Escargot Catalog Summary
| `AppType` | `CategoryID` | Category Name | `EntryID` | Name | `Width` x `Height` | `URL` | `AppIconURL` |
|---|---|---|---|---|---|---|---|
| `0` (Game) | `101` | Microsoft | `101000` | Tic Tac Toe | `390` x `500` | `http://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/app-data/tic-tac-toe/tictactoe.htm` | *(empty — uses default icon)* |
| `0` (Game) | `103` | Escargot Games | `103000` | Wordle (beta) | `490` x `350` | `http://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/app-data/wordle/index.html` | `.../app-data/wordle/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102000` | Reversi | `490` x `350` | `.../app-data/messbe/reversi/reversi.htm` | `.../messbe/reversi/images/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102001` | Yahtzee | `530` x `390` | `.../app-data/messbe/yahtzee/yahtzee.htm` | `.../messbe/yahtzee/images/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102002` | Battleships | `530` x `390` | `.../app-data/messbe/battleships/battleships.htm` | `.../messbe/battleships/images/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102003` | Chess | `530` x `410` | `.../app-data/messbe/chess/chess.htm` | `.../messbe/chess/images/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102004` | Nine Men's Morris | `530` x `390` | `.../app-data/messbe/morris/index.en-us.html` | `.../messbe/morris/imgs/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102005` | Connect 4 | `530` x `390` | `.../app-data/messbe/connect4/connect4.htm` | `.../messbe/connect4/images/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102006` | Memory | `530` x `390` | `.../app-data/messbe/memory/memory.htm` | `.../messbe/memory/images/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102007` | Tetris | `530` x `390` | `.../app-data/messbe/tetris/tetris.htm` | `.../messbe/tetris/images/icon.png` |
| `0` (Game) | `102` | Mess.be Games | `102008` | Backgammon | `530` x `410` | `.../app-data/messbe/backgammon/backgammon.htm` | `.../messbe/backgammon/images/icon.png` |
| `1` (Activity) | `100` | Escargot Activities | `100000` | Whiteboard | `500` x `500` | `http://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/app-data/whiteboard/index.html` | `.../app-data/whiteboard/icon.png` |

### 1.4 Single-Entry Metadata & Permission Lookup (`GetAppEntry` & `CP4AppEntry`)
Both when initiating an invite (`CP4Data::GetAppEntry` at `0x005e14d3`) and before opening the browser pane (`CGameBrowser::StartDownload` at `0x005ddf4f`), WLM calls `CSGetAppEntryInfo(bstrEntryId)` (`0x005b27aa`) using the format string at `0x0043e9ac`:
```http
GET http://apps.escargot.nina.chat/activities/service/?op=GetAppEntry&ID=103000&ver=14.0.8117.416
```
Example live response (`ID=103000`):
```xml
<?xml version="1.0" encoding="utf-8"?>
<Entry>
  <EntryID>103000</EntryID>
  <Error/>
  <Locale>en-us</Locale>
  <Kids>1</Kids>
  <Page>1</Page>
  <CategoryID>103</CategoryID>
  <Sequence>1</Sequence>
  <Name>Wordle (beta)</Name>
  <Description>Guess the words faster than your friend!</Description>
  <URL>http://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/app-data/wordle/index.html</URL>
  <IconURL/>
  <AppIconURL>http://static.levelleap.com/sites/messaging/apps.escargot.nina.chat/app-data/wordle/icon.png</AppIconURL>
  <SpecialIconURL/>
  <Type>game</Type>
  <Height>350</Height>
  <Width>490</Width>
  <Location>side</Location>
  <MinUsers>2</MinUsers>
  <MaxUsers>2</MaxUsers>
  <PassportSiteID>0</PassportSiteID>
  <EnableIP>False</EnableIP>
  <ActiveX>False</ActiveX>
  <SendFile>False</SendFile>
  <SendIM>False</SendIM>
  <ReceiveIM>False</ReceiveIM>
  <ReplaceIM>True</ReplaceIM>
  <Windows>False</Windows>
  <MaxPacketRate>120</MaxPacketRate>
  <LogURL/>
  <UserProperties>False</UserProperties>
  <SubscriptionURL/>
  <AppType>0</AppType>
  <Hidden>false</Hidden>
</Entry>
```

`CP4AppEntry` (`0x005dc36c`–`0x005dc9d0`) initializes default values (`CP4AppEntry::InitDefaults` at `0x005dc688`) and parses the `<Entry>` XML into a property map keyed by integer IDs:

| Prop ID | XML Element | VARTYPE | Default Value | Purpose in `msnmsgr.exe` |
|---|---|---|---|---|
| `0x01` (1) | `<EntryID>` | `VT_I4` | `0` | Application / Game ID |
| `0x02` (2) | `<Error>` | `VT_BSTR` | `"revoked"` | Must be empty (`""`) in XML; if non-empty, `CGameBrowser` aborts with `0x8100036b` |
| `0x04` (4) | `<Kids>` | `VT_I4` | `0` | `1` if allowed for kid accounts |
| `0x0B` (11) | `<Name>` | `VT_BSTR` | `""` | Display name of the game/activity |
| `0x0D` (13) | `<URL>` | `VT_BSTR` | `""` | Hosted HTML URL navigated by `CGameBrowser` |
| `0x10` (16) | `<Height>` | `VT_I4` | `0` | Side-pane pixel height |
| `0x11` (17) | `<Width>` | `VT_I4` | `0` | Side-pane pixel width |
| `0x12` (18) | `<Location>` | `VT_BSTR` | `"side"` | Pane location (`"side"`) |
| `0x13` (19) | `<MinUsers>` | `VT_I4` | `0` | Minimum players (`1` or `2`) |
| `0x14` (20) | `<MaxUsers>` | `VT_I4` | `0` | Maximum players (`1` or `2`) |
| `0x15` (21) | `<PassportSiteID>` | `VT_I4` | `0` | Optional Passport auth ticket site ID (`0x005de03f`) |
| `0x16` (22) | `<EnableIP>` | `VT_BOOL` | `false` | Gates `IUser2.LocalIP` and `IUser2.GlobalIP` (`CP4Users` flag at `0x005dadf6`) |
| `0x17` (23) | `<ActiveX>` | `VT_BOOL` | `false` | Required alongside `<SendFile>` to enable file transfer bit `0x08` |
| `0x18` (24) | `<SendFile>` | `VT_BOOL` | `false` | If `ActiveX && SendFile`, sets bit `0x08` on `CP4Channel` permissions (`0x005db035`) |
| `0x19` (25) | `<SendIM>` | `VT_BOOL` | `false` | Sets bit `0x01` on `CP4Channel` permissions (gates `Channel.SendIM` / `EnterIM`) |
| `0x1A` (26) | `<ReceiveIM>` | `VT_BOOL` | `false` | Sets bit `0x02` on `CP4Channel` permissions (gates `OnIMReceived`) |
| `0x1B` (27) | `<ReplaceIM>` | `VT_BOOL` | `false` | Sets bit `0x04` on `CP4Channel` permissions (gates `Channel.SendIMAsUser` at `0x005d7368`) |
| `0x1C` (28) | `<Windows>` | `VT_BOOL` | `false` | Allows opening secondary popup browser windows (e.g. Mess.be `help.htm`) |
| `0x1E` (30) | `<LogURL>` | `VT_BSTR` | `""` | Optional telemetry/logging URL |
| `0x1F` (31) | `<UserProperties>` | `VT_BOOL` | `false` | Gates `IUser2.Email`, `IUser2.PUID`, and `IUser2.CID` (`0x005dbff9`) |
| `0x20` (32) | `<SubscriptionURL>` | `VT_BSTR` | `""` | Optional subscription check URL |

---

## 2. Invitation System (MSNSLP / P4 EUF)

### 2.1 P4 EUF-GUID and Session Parameters
`CP4EUF::Register` (`0x005de92d`) registers the P4 session handler with the MSNP2P manager using:
- **`EUF-GUID`**: **`{6A13AF9C-5308-4F35-923A-67E8DDA40C2F}`** (raw bytes `9c af 13 6a 08 53 35 4f 92 3a 67 e8 dd a4 0c 2f` at VA `0x00448f48`)
- **`AppID`**: The integer **`EntryID`** (e.g. `101000` for Tic Tac Toe; defaults to `6` if `EntryID == 0` at `0x005df2af`)
- **`RequestFlags`**: **`16`** (`0x10`, passed to `CreateSlave` at `0x005df2e3`)

### 2.2 Invitation `Context` Payload Format (`"%d;%s;%s"`)
When a local user picks a game from the menu or directory (`CP4Data::OnAppEntryResponseReceived` at `0x005e0e11` -> `WM_USER+2` handler at `0x005e1bfe`):
1. **Single-Player check (`0x005e1cab`)**: If `MinUsers == 1 && MaxUsers == 1`, WLM opens `CGameBrowser` locally without sending an MSNSLP invitation.
2. **Two-Player formatting (`0x005e1cd9`)**: If `MinUsers == 2 && MaxUsers == 2`, `FormatP4Context` (`0x005e2224`) formats a wide string using `"%d;%s;%s"` (`0x00449eec`):
   ```
   <EntryID>;<KidsFlag>;<AppName>
   ```
   - `<EntryID>`: Decimal integer `EntryID` (e.g. `101000`).
   - `<KidsFlag>`: `"1"` (`0x004119b8`) if `Kids != 0`, or `"0"` (`0x00413d6c`) if `Kids == 0`.
   - `<AppName>`: `<Name>` string from `GetAppEntry` (e.g. `"Tic Tac Toe"`).
   - Example context string: `"101000;1;Tic Tac Toe"`.
3. **UTF-16LE encoding (`CP4EUF::SendInvitation` at `0x005df22f`)**:
   - Computes `cbContext = wcslen(pwszContext) * 2` (`0x005df2c2`–`0x005df2ca` — **UTF-16LE byte count without a trailing NUL character**).
   - Passes `(BYTE*)pwszContext` and `cbContext` to `CreateSlave(bstrPeerEmail, 0, 0x10, P4_EUF_GUID, pbContext, cbContext, dwAppId, &pSession)` (`0x005df2ea`).
   - In the MSNSLP `INVITE MSNMSGR:<peer> MSNSLP/1.0` body, `Context:` is the Base64 encoding of those UTF-16LE bytes:
     ```
     EUF-GUID: {6A13AF9C-5308-4F35-923A-67E8DDA40C2F}
     SessionID: <sessionId>
     AppID: 101000
     Context: MQAwADEAMAAwADAAOwAxADsAVABpAGMAIABUAGEAYwAgAFQAbwBlAA==
     ```

### 2.3 Invitation Lifecycle & Browser Launch Timing
1. **Inviter Sends Invite (`CP4EUF::SendInvitation` at `0x005df22f`)**:
   - Verifies peer is online (`0x005df25b`, else `0x8100031e`), peer capabilities support P4 (`0x005df270`, else `0x82000009`), and `CanStartApp` (`0x005de735`).
   - Creates `CP4Slave` (`0x005de5ea`, `bIsInviter = 1`), stores `EntryID` and `AppName` on `CP4Slave` (`0x005e2320`), inserts into `CP4EUF`'s active slave map (`0x005deb11`), and posts UI event `0x56` ("Invitation Sent").
   - **Important**: The inviter does **NOT** open `CGameBrowser` when sending the `INVITE`. It waits until the invitee accepts.
2. **Invitee Receives Invite (`CP4EUF::InviteReceived` at `0x005decbe`)**:
   - Copies `cbContextData / 2` wide characters from `pbContextData`, splits on `';'` (`0x004116e4`) via `ParseP4Context` (`0x005e224d`) into `[0] = EntryID`, `[1] = KidsFlag`, `[2] = AppName`.
   - Checks kid restrictions (`0x005dedf6`; if disallowed, immediately declines with `0x8100036a`).
   - Creates `CP4Slave` (`bIsInviter = 0`) and posts UI event `0x55` ("Invitation Received") to display the Accept / Decline prompt in the chat window.
3. **Invitee Accepts (`CP4EUF::InviteAcceptLocal` at `0x005df59c`)**:
   - Verifies the conversation is 1-on-1 (`0x005df5eb`, else `0x81000371`), checks `CanStartApp` (`0x005df61a`), posts UI event `0x5f`, calls `CP4Slave::Accept(0, 0)` (`0x005e1e9a` -> `pSession->Accept(1, 0, 0)` which sends MSNSLP `200 OK` and begins P2P transport setup), and **immediately calls `CreateP4Browser` (`0x005debbd` at `0x005df6a2`)** to open the game browser on the invitee side.
4. **Inviter Receives Acceptance (`CP4EUF::OnSessionAccepted` at `0x005df755`)**:
   - Triggered when `CP4Slave::OnSessionInfoChanged` (`0x005e210a`) receives state `0` (Accepted).
   - Posts UI event `0x5c` and calls **`CreateP4Browser` (`0x005debbd` at `0x005df7d8`)** to open the game browser on the inviter side.
5. **Declining, Cancelling, or Closing (`CP4Slave::Decline` at `0x005e1ec3`, `CP4Slave::Close` at `0x005e1e6f`)**:
   - Sends a 4-byte little-endian `HRESULT` as the SLP context payload:
     - `0x80070005` (`E_ACCESSDENIED`): Invitee declined the invitation (`InviteRejectLocal` at `0x005dcfa4`).
     - `0x81000374`: User cancelled or closed the game session (`0x005dde42`, `0x005e1ba3`).
     - `0x8100036a`: Invalid context or Kid account restriction (`0x005dedb3`, `0x005dee04`).
     - `0x81000371`: Multipoint (group chat) not allowed for 2-player game (`0x005df5f6`).

---

## 3. Browser Host, `P4PLib` (`window.external`), and JS Event Dispatch

### 3.1 `P4PLib` TypeLib (`msnmsgr.exe\6`, `{DF7AC326-0B93-4A84-AC96-E2F9A21FD765}`)
When `CGameBrowser` hosts the game's HTML page, `window.external` returns `CP4External` implementing `IP4PExternal`:

#### `IP4PExternal` (`{677225B9-A237-419B-A30C-C7D47BB85BF5}`)
| DISPID | Member | Signature / Return Type | Description |
|---|---|---|---|
| `1` | `Users` | `[propget] IUsers*` | Returns `CP4Users` collection |
| `2` | `Channel` | `[propget] IChannel*` | Returns `CP4Channel` communication channel |
| `3` | `Messenger` | `[propget] IMessengerUI*` | Returns `IMessengerUI` (`Options`, `Phone`) |
| `4` | `CloseApp` | `void CloseApp()` | Closes the game side-pane (`WM_CLOSE` to `CGameBrowser` at `0x005db1fb`) |

#### `IUsers` (`{67B300B4-C88D-4145-8693-09D818543173}`)
| DISPID | Member | Signature / Return Type | Description |
|---|---|---|---|
| `0` | `Item` | `IUser2* Item(long lPos)` | Returns user at index `0` or `1` |
| `1` | `Me` | `[propget] IUser2*` | Returns the local user's `IUser2` instance |
| `2` | `Inviter` | `[propget] IUser2*` | Returns the inviting user's `IUser2` instance |
| `3` | `Count` | `[propget] long` | Returns `2` for two-player games (`1` for single-player) |
| `-4` | `_NewEnum` | `[propget] IUnknown*` | Standard COM enumerator |

> **Critical Implementation Detail (`Users.Me == Users.Inviter`)**:
> Hosted games (including Microsoft Tic Tac Toe, Wordle, and all Mess.be games) determine who is Player 1 / Inviter by evaluating **`window.external.Users.Me == window.external.Users.Inviter`** in JavaScript!
> In `CP4Users::CreateUsers` (`0x005dc077`), `CP4Users` allocates two `CP4User` instances and assigns the exact same object reference to `m_spMe` and `m_spInviter` on the inviter's client (`0x005dc125`), and distinct references on the invitee's client. Any WebView/JS bridge implementation **must** ensure `window.external.Users.Me === window.external.Users.Inviter` evaluates to `true` for the inviter and `false` for the invitee (and similarly in `Users.Item(0)` / `Users.Item(1)`).

#### `IUser2` (`{759EA803-31B7-408F-B991-67A2B3AA5E09}`)
| DISPID | Member | Return Type | Permission Requirement | Description |
|---|---|---|---|---|
| `1` | `Email` | `BSTR` | `<UserProperties>True</UserProperties>` | Sign-in email address (returns `0x81000368` if disabled) |
| `2` | `Name` | `BSTR` | *Always accessible* | Display / friendly name |
| `3` | `LocalIP` | `BSTR` | `<EnableIP>True</EnableIP>` | Local IPv4 address |
| `4` | `GlobalIP` | `BSTR` | `<EnableIP>True</EnableIP>` | Global IPv4 address |
| `5` | `PUID` | `VARIANT` | `<UserProperties>True</UserProperties>` | Passport Unique ID |
| `6` | `CID` | `VARIANT` | `<UserProperties>True</UserProperties>` | Contact ID |

#### `IChannel` (`{9C48D06F-9426-4169-9527-5372C9F591B8}`)
| DISPID | Member | Signature / Return Type | Permission Requirement | Description |
|---|---|---|---|---|
| `1` | `Data` | `[propget] VARIANT` | Always | Payload of the current `OnDataReceived` event (`0x005d6cd2`) |
| `2` | `Error` | `[propget] IError*` | Always | Error object (`Data` `DISPID 1`, `Type` `DISPID 2`) during `OnDataError` |
| `3` | `Type` | `[propget] CONNECT_TYPE` | Always | `0` = `CT_DIRECT`, `1` = `CT_INDIRECT` (switchboard-bridged), `2` = `CT_DISCONNECTED` |
| `4` | `SendData` | `void SendData(VARIANT vDataValue)` | Always | Sends `vDataValue` to peer over P4 P2P channel (`0x005d9922`) |
| `5` | `EnterIM` | `void EnterIM(BSTR bstrIM)` | `<SendIM>True</SendIM>` | Populates the chat input box with `bstrIM` (`0x005d72db`) |
| `6` | `SendIM` | `void SendIM(BSTR bstrIM)` | `<SendIM>True</SendIM>` | Sends an automated IM prefixed with app name (`0x005d8c80`) |
| `7` | `IM` | `[propget/put] BSTR` | `<ReceiveIM>True</ReceiveIM>` | Current IM string during `OnIMReceived` (`0x005d6db7`) |
| `8` | `SendIMAsUser` | `void SendIMAsUser(BSTR bstrIM)` | `<ReplaceIM>True</ReplaceIM>` | Sends a normal chat message as the local user (`0x005d7368`) |
| `9` | `CancelSendFile` | `void CancelSendFile(IDispatch* pdispVal)` | `<ActiveX>` & `<SendFile>` | Cancels an in-progress P4 file transfer (`0x005d8eb0`) |
| `10` | `SendFile` | `IDispatch* SendFile(IDispatch* pdispFileInfo)` | `<ActiveX>` & `<SendFile>` | Starts a P4 file transfer (`0x005d942e`) |
| `11` | `FileInfo` | `[propget] IDispatch*` | `<ActiveX>` & `<SendFile>` | Current `IFileInfo` during file events (`0x005d6e26`) |
| `12` | `Initialize` | `void Initialize()` | Always | Signals local game is loaded and sends `REMOTE APP LOADED` (`0x005d9316`) |

#### `IAppDirExternal` (`{1A6BAD83-51A6-4C5A-93D3-38343A86EDAA}`) — Exposed to `<AppDirPageURL>` Directory Browser
| DISPID | Member | Signature |
|---|---|---|
| `1` | `StartApp` | `void StartApp(BSTR bstrAppName, long lAppId, long lMaxUsers, long lMinUsers, long lKidsFlag)` |
| `2` | `Initialize` | `void Initialize()` |
| `3` | `StartApp2` | `void StartApp2(BSTR bstrAppName, long lAppId, long lMaxUsers, long lMinUsers, long lKidsFlag, BSTR bstrSubscriptionURL)` |

### 3.2 How `DChannelEvents` Automatically Invoke Global `Channel_<EventName>()` JS Functions
`DChannelEvents` (`{B1F1C9F9-52BF-4E02-9BB8-985ABB6694CF}`) defines 11 events on `CP4Channel`:

| DISPID | Event Name | Global JS Function Invoked | Description |
|---|---|---|---|
| `1` | `OnDataReceived(vData)` | `Channel_OnDataReceived()` | Peer sent data via `Channel.SendData`; JS reads `window.external.Channel.Data` |
| `2` | `OnDataError(pdispError)` | `Channel_OnDataError()` | Send/receive error; JS reads `window.external.Channel.Error` |
| `3` | `OnTypeChanged(eConnectionType)` | `Channel_OnTypeChanged()` | `Channel.Type` changed (`0 = DIRECT`, `1 = INDIRECT`, `2 = DISCONNECTED`) |
| `4` | `OnAppClose()` | `Channel_OnAppClose()` | Local user is closing the game pane |
| `5` | `OnIMReceived(bstrIM)` | `Channel_OnIMReceived()` | Chat IM received (if `<ReceiveIM>True</ReceiveIM>`) |
| `6` | `OnFileReceived(pdispFile)` | `Channel_OnFileReceived()` | File transfer received |
| `7` | `OnRemoteAppClosed()` | `Channel_OnRemoteAppClosed()` | Peer closed their game pane / MSNSLP session ended |
| `8` | `OnRemoteAppLoaded()` | `Channel_OnRemoteAppLoaded()` | Both peers have called `Channel.Initialize()` and exchanged `REMOTE APP LOADED` |
| `9` | `OnFileProgress(pdispFile)` | `Channel_OnFileProgress()` | File transfer progress update |
| `10` | `OnSendFileCancelled(pdispFile)` | `Channel_OnSendFileCancelled()` | Peer cancelled file transfer |
| `11` | `OnSendIMSent(bstrIM)` | `Channel_OnSendIMSent()` | `SendIM` completed |

**How WLM binds these events (`CP4External` at `0x005dad1b`–`0x005db770`)**:
1. When `CP4External::Init` (`0x005db751`) runs, `CP4External::BuildEventNameMap` (`0x005db632`) loads the `ITypeInfo` for `DChannelEvents` from `msnmsgr.exe\6` and builds a hash table (`0x34(%esi)`) mapping each `DISPID` to its `BSTR` method name (e.g. `1 -> "OnDataReceived"`, `8 -> "OnRemoteAppLoaded"`).
2. When the game HTML finishes loading, `CP4External::OnDocumentComplete` (`0x005dae2d` -> `0x005dadd1`) queries `IHTMLDocument2::get_Script(&m_spScriptDisp)` (`0x64(%esi)`), obtaining the `IDispatch*` of the page's global `window` script object.
3. Whenever `CP4Channel` fires an event on `DChannelEvents`, `CP4External::Invoke` (`0x005db489`) looks up the `DISPID` in its hash table, prepends **`"Channel_"`** (`0x0044886c` at `0x005db4ba`) to form `"Channel_" + EventName`, resolves the function's `DISPID` on `m_spScriptDisp->GetIDsOfNames` (`0x005db4e2`), and calls `m_spScriptDisp->Invoke(...)` (`0x005db50e`).

### 3.3 The `Channel.Initialize()` <-> `OnRemoteAppLoaded` Synchronization Handshake
1. In the game's `window.onload` handler, JS calls `window.external.Channel.Initialize()` (`CP4Channel::Initialize` at `0x005d9316`).
   - For single-player games (`m_pSlave == NULL` at `0x005d9325`), `Initialize()` immediately fires `Channel_OnRemoteAppLoaded()` (`DISPID 8`) and returns.
   - For two-player games, `Initialize()` sets `m_bInitialized = 1` (`0x44(%esi)`), calls `SendRemoteAppLoaded()` (`0x005d7428`), and if `m_bRemoteAppLoaded` (`0x45(%esi)`) was already set by an incoming packet from the peer, immediately fires `Channel_OnRemoteAppLoaded()`.
2. In `CP4Channel::SendRemoteAppLoaded` (`0x005d7428`):
   - If `m_eConnectType == 2` (`CT_DISCONNECTED`, meaning the MSNP2P transport is still negotiating), it sets `m_bPendingRemoteAppLoaded = 1` (`0x104(%esi)`).
   - As soon as `CP4Channel::OnConnected` (`0x005d89db`) transitions `m_eConnectType` to `0` (`CT_DIRECT`) or `1` (`CT_INDIRECT`), it checks `m_bPendingRemoteAppLoaded` (`0x005d89f4`) and calls `SendRemoteAppLoaded()` (`0x005d89fd`) to send the `00 00 01 00` control packet.
3. When the peer receives `00 00 01 00` (`CP4Channel::OnReceived` at `0x005da6de`):
   - It sets `m_bRemoteAppLoaded = 1` (`0x45(%esi)`).
   - If local `m_bInitialized == 1`, it immediately fires `Channel_OnRemoteAppLoaded()`; otherwise, it waits until local JS calls `Channel.Initialize()`.

---

## 4. P4 Binary Wire Format Over MSNP2P (`CP4Channel`)

All P4 control messages and game moves are transmitted inside MSNP2P data packets associated with the P4 session (`EUF-GUID = {6A13AF9C-5308-4F35-923A-67E8DDA40C2F}`, `AppID = EntryID`).

### 4.1 4-Byte P4 Packet Header (`CP4Channel::OnReceived` at `0x005da67e`)
Every P4 packet is at least 4 bytes long:

```
 Offset  0               1               2               3
        +---------------+---------------+---------------+---------------+
        |I| Version (0) |     0x00      |   wMsgType / 0x0000 (LE)      |
        +---------------+---------------+---------------+---------------+
         ^
         |-- Bit 7 (0x80):
               0 = Internal Control Message (Byte 0 == 0x00)
               1 = Application Data Message (Byte 0 == 0x80)
         |-- Bits 0..6 (0x7F):
               Protocol Version = 0 (if != 0, rejected at 0x005da699)
```

### 4.2 Internal Control Packets (`Byte 0 == 0x00`, `SendInternalMsg` at `0x005d7392`)
When `Byte 0 == 0x00`, `Byte 1` is `0x00` and `Bytes 2..3` (`uint16_t` little-endian) specify `wMsgType` (`0x005da6c0`):

| `wMsgType` | Wire Bytes (Hex) | Total Size | Meaning |
|---|---|---|---|
| `1` | `00 00 01 00` | 4 bytes | **`REMOTE APP LOADED`** — Sent by `Channel.Initialize()` (`0x005d745a`) |
| `2` | `00 00 02 00 <dwCookie:4B LE>` | 8 bytes | **`REMOTE CANCEL FILE`** — Cancels file transfer with cookie `dwCookie` |
| `3` | `00 00 03 00 <dwCookie:4B LE>` | 8 bytes | **`FILE COOKIE`** — Associates file transfer cookie `dwCookie` |

### 4.3 Application Data Packets (`Byte 0 == 0x80`, `CP4Channel::SendData` at `0x005d9922`)
When `Channel.SendData(vDataValue)` is called:
- Rate-limited by a sliding window (`CP4Channel::CheckRateLimit` at `0x005d75cf`): max **`120` packets per `60` seconds** (`0x0040f074`–`0x0040f07c`).
- Maximum serialized payload size: **`0x10000` (65,536) bytes** (`0x005d99cb`, else returns `0x81000370`).
- **Bytes 0..3**: **`80 00 00 00`** (`0x005d99aa`).
- **Bytes 4+**: `vDataValue` serialized via ATL `CComVariant::WriteToStream` (`0x00588636`) and deserialized on the peer via `CComVariant::ReadFromStream` (`0x005d8777`):

#### `CComVariant` Stream Layout (`Bytes 4+`)
- **Bytes 4..5**: `uint16_t vt` (little-endian `VARTYPE`).
- **Bytes 6+**: Value payload according to `vt`:

| `VARTYPE` (`vt`) | Hex (LE) | Payload Format (`Bytes 6+`) |
|---|---|---|
| `VT_BSTR` (`8`) | `08 00` | `uint32_t cbByteLen` (LE, byte count of UTF-16LE string **including** the 2-byte `0x0000` NUL terminator; `0` if empty/null) followed by `cbByteLen` bytes of UTF-16LE characters ending in `00 00` (`CComBSTR::WriteToStream` at `0x00587d1c`, `ReadFromStream` at `0x005d6f5f`). |
| `VT_I4` (`3`), `VT_UI4` (`19`), `VT_INT` (`22`), `VT_UINT` (`23`), `VT_R4` (`4`), `VT_ERROR` (`10`) | `03 00`, etc. | 4 bytes little-endian |
| `VT_I2` (`2`), `VT_UI2` (`18`), `VT_BOOL` (`11`) | `02 00`, `0b 00` | 2 bytes little-endian (`VT_BOOL`: `0xFFFF` = true, `0x0000` = false) |
| `VT_UI1` (`17`), `VT_I1` (`16`) | `11 00`, `10 00` | 1 byte |
| `VT_R8` (`5`), `VT_CY` (`6`), `VT_DATE` (`7`), `VT_I8` (`20`), `VT_UI8` (`21`) | `05 00`, etc. | 8 bytes little-endian |

#### Example Wire Packet (`Channel.SendData("4")` in Tic Tac Toe)
```
Offset  Hex Bytes               Explanation
------  ----------------------  --------------------------------------------------
00..03  80 00 00 00             P4 Application Data header (Bit 7 = 1, Version = 0)
04..05  08 00                   VARTYPE = VT_BSTR (8)
06..09  04 00 00 00             cbByteLen = 4 bytes ((1 char + 1 NUL) * 2)
0A..0D  34 00 00 00             UTF-16LE L"4\0" ('4' = 0x0034, NUL = 0x0000)
```

---

## 5. JavaScript Move Protocols of Escargot's Hosted Games

All hosted games on Escargot communicate by passing strings (`VT_BSTR`) to `window.external.Channel.SendData(str)` and reading `window.external.Channel.Data` inside `function Channel_OnDataReceived()`.

### 5.1 Microsoft Tic Tac Toe (`EntryID = 101000`, `tictactoe.htm`)
- **Player Assignment**:
  - Inviter (`window.external.Users.Me == window.external.Users.Inviter`) is Player `0` (`X`).
  - Invitee is Player `1` (`O`).
  - Starting turn in Round `giRoundNum` (starts at `1`): `giTurn = (giRoundNum + 1) % 2` (Player `0` starts Round 1, Player `1` starts Round 2, etc.).
- **Messages (`Channel.SendData`)**:
  - `"0"` .. `"8"`: Marks cell index `0..8` (row-major 3x3 grid: top-left `0`, center `4`, bottom-right `8`).
  - `"READY"`: Sent when clicking the banner after a game ends (`GameOver == 1`). If the local client is already in `GameOver == 1` and hasn't sent `"READY"` yet when it receives `"READY"` from the peer, it automatically echoes `"READY"` back and both call `Channel_OnRemoteAppLoaded()` to start the next round.
  - `"GONE"`: Sent inside `Channel_OnAppClose()` when a player closes the game pane.

### 5.2 Escargot Wordle (`EntryID = 103000`, `wordle/index.html` + `wordle/game.js`)
- **Handshake after `Channel_OnRemoteAppLoaded()`**:
  - Inviter (`Users.Me == Users.Inviter`) selects a random 5-letter uppercase word from `WORDS` and sends `"WRD <WORD>"` (e.g. `"WRD HOUSE"`).
  - Invitee receives `"WRD <WORD>"`, sets its target `word`, replies `"WRD OK"`, and calls `Ready()`.
  - Inviter receives `"WRD OK"` and calls `Ready()`.
- **Gameplay Messages**:
  - `"KEY <CHAR>"`: Sent on each letter keystroke (`A`–`Z`) so the opponent's mini-grid shows live tile activity.
  - `"KEY BACKSPACE"`: Sent when deleting a letter.
  - `"KEY ENTER"`: Sent when submitting a 5-letter guess row; updates the opponent's preview grid colors and checks win/loss state.
- **Rematch Messages**:
  - `"REM"`: Requests a rematch after round completion.
  - `"NEW"`: Sent when accepting a rematch (`rematchRequested == true`); resets boards and triggers the inviter to send a new `"WRD <WORD>"`.
- **Chat Integration**:
  - Calls `window.external.Channel.SendIMAsUser("Wordle: ...")` when a round finishes (enabled via `<ReplaceIM>True</ReplaceIM>`).

### 5.3 Mess.be Games — e.g. Reversi (`EntryID = 102000`, `messbe/reversi/reversi.htm` + `messbe/common/common.js`)
- **Player Assignment (`IsInviter()` in `common.js`)**:
  - Inviter (`Users.Inviter == Users.Me`) is `PC_WHITE = 1` and moves first (`g_Turn = PC_WHITE`).
  - Invitee is `PC_RED = 2`.
- **Messages**:
  - `"<x>,<y>"` (e.g. `"2,3"`): Places a piece at 0-indexed board coordinates `(x, y)`.
  - `"hint<x>,<y>"` (e.g. `"hint2,3"`): Highlights cell `(x, y)` on the opponent's board as a hint.
  - `"res"`: Requests a game restart (or confirms restart if the peer already requested one).
  - `"nor"`: Declines the peer's restart request.

### 5.4 Escargot Whiteboard (`EntryID = 100000`, `AppType = 1`, `whiteboard/index.html`)
- Buffers outgoing commands and flushes them every 50 ms joined by `|`:
  - `"U:<mouseButton>:<x>:<y>:<strokeWeight>:<color>"` (e.g. `"U:1:120:150:3:black"`): Draws a stroke segment (`mouseButton == 1` draws with `color` and `strokeWeight`; `mouseButton == 2` erases with white).
  - `"C"`: Clears the canvas.
