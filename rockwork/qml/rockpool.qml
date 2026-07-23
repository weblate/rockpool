import QtQuick 2.2
import Sailfish.Silica 1.0
import RockPool 1.0
import Nemo.DBus 2.0
import "pages"

/*!
    \brief MainView with a Label and Button elements.
*/

ApplicationWindow {
    id: rockPool
    initialPage: Qt.resolvedUrl("pages/LoadingPage.qml")
    cover: Qt.resolvedUrl("cover/CoverPage.qml")
    property int curPebble: -1
    property var sysProfiles: [ ]

    ServiceController {
        id: serviceController
        Component.onCompleted: initService()
    }

    Pebbles {
        id: pebbles
        onCountChanged: loadStack()
        onConnectedToServiceChanged: loadStack();
    }
    DBusInterface {
        id: profiled
        service: "com.nokia.profiled"
        path: "/com/nokia/profiled"
        iface: "com.nokia.profiled"
    }
    function getProfiles() {
        if(sysProfiles.length===0) {
            profiled.typedCall("get_profiles",[],
               function(r){sysProfiles=[].concat(sysProfiles,r);console.log("Now",sysProfiles,sysProfiles.length)},
               function(e){console.log("com.nokia.profiled error",e)})
        }
    }
    function initService() {
        if (!pebbles.connectedToService && !serviceController.serviceRunning) {
            console.log("Service not running. Starting now.");
            serviceController.startService();
        }
        // No version-mismatch restart: libpebble3d versions independently of the UI
        // (the old check bounced the daemon on every app launch), and the daemon RPM
        // already try-restarts on upgrade.
    }
    function stopService() {
        console.log("Request to stop and disable service");
        serviceController.stopService()
    }
    function restartService() {
        console.log("Request to restart service");
        serviceController.restartService()
    }

    function loadStack() {
        if (pebbles.connectedToService) {
            pageStack.clear()
            if (pebbles.count === 1) {
                curPebble = 0;
                pageStack.push(Qt.resolvedUrl("pages/MainMenuPage.qml"), {pebble: pebbles.get(curPebble)})
            } else {
                pageStack.push(Qt.resolvedUrl("pages/PebblesPage.qml"))
            }
        } else {
            console.log("Waiting for service")
            if(curPebble>=0) {
                pageStack.clear();
                pageStack.push(initialPage);
                curPebble = -1;
            }
        }
    }
    Component.onCompleted: loadStack()
    function getCurPebble() {
        if(curPebble>=0) return pebbles.get(curPebble);
        return null;
    }
}
