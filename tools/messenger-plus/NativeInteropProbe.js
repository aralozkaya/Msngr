// Read-only native oracle and receiver for synthetic interoperability messages.
// Messages are sent through Messenger's normal typing field, not from this script.
var plusInteropPeer = "";
function plusInteropQuote(s) {
    return '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"')
        .replace(/[\u0000-\u001f]/g, function(c) {
            return '\\u' + ('0000' + c.charCodeAt(0).toString(16)).slice(-4);
        }) + '"';
}
function plusInteropRead(name) {
    var f = new ActiveXObject('Scripting.FileSystemObject')
        .OpenTextFile(MsgPlus.ScriptFilesPath + '\\' + name, 1, false, -1);
    var result = eval('(' + f.ReadAll() + ')'); f.Close(); return result;
}
function plusInteropWrite(name, text, append) {
    var fs = new ActiveXObject('Scripting.FileSystemObject');
    var path = MsgPlus.ScriptFilesPath + '\\' + name;
    var f = append ? fs.OpenTextFile(path, 8, true, -1) : fs.CreateTextFile(path, true, true);
    f.Write(text); f.Close();
}
function OnEvent_Initialize() {
    try {
        var fs = new ActiveXObject('Scripting.FileSystemObject');
        plusInteropPeer = plusInteropRead('interop-config.json').peer.toLowerCase();
        var cases = plusInteropRead('interop-cases.json');
        if (fs.FileExists(MsgPlus.ScriptFilesPath + '\\interop-edge-cases.json')) {
            cases = cases.concat(plusInteropRead('interop-edge-cases.json'));
        }
        var results = [];
        for(var i = 0; i < cases.length; i++) {
            var c = cases[i];
            results.push('{"id":' + plusInteropQuote(c.id) + ',"source":' + plusInteropQuote(c.source) +
                ',"plain":' + plusInteropQuote(MsgPlus.RemoveFormatCodes(c.source)) + '}');
        }
        plusInteropWrite('interop-oracle.json', '[' + results.join(',') + ']', false);
        plusInteropWrite('interop-status.json', '{"account":' + plusInteropQuote(Messenger.MyEmail) +
            ',"cases":' + cases.length + ',"ready":true}', false);
    } catch (e) {
        plusInteropWrite('interop-status.json', '{"error":' + plusInteropQuote(e.description) + '}', false);
    }
}
function OnEvent_ChatWndReceiveMessage(chat, origin, message, kind) {
    var contacts = new Enumerator(chat.Contacts), count = 0, isPeer = false;
    for (; !contacts.atEnd(); contacts.moveNext()) {
        count++;
        if (String(contacts.item().Email).toLowerCase() == plusInteropPeer) isPeer = true;
    }
    if (isPeer && count == 1 && message.indexOf('PLUS-TX-') >= 0) {
        plusInteropWrite('interop-received.jsonl', '{"source":' + plusInteropQuote(message) +
            ',"plain":' + plusInteropQuote(MsgPlus.RemoveFormatCodes(message)) + '}\r\n', true);
    }
    return message;
}
