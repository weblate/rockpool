#ifndef SAILFISHPLATFORM_H
#define SAILFISHPLATFORM_H

#include "libpebble/platforminterface.h"
#include "libpebble/enums.h"
#include "voicecallmanager.h"
#include "voicecallhandler.h"
#include "musiccontroller.h"

#include <QDBusInterface>
#include <QDBusContext>

class QDBusPendingCallWatcher;
class VoiceCallManager;
class OrganizerAdapter;
class watchfish::MusicController;

class SailfishPlatform : public PlatformInterface, public QDBusContext
{
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "org.freedesktop.Notifications")
    Q_PROPERTY(QDBusInterface* interface READ interface)


public:
    SailfishPlatform(QObject *parent = 0);
    QDBusInterface* interface() const;

    void sendMusicControlCommand(MusicControlButton controlButton) override;
    MusicMetaData musicMetaData() const override;
    void hangupCall(uint cookie) override;
    QHash<QString, QString> getCategoryParams(QString category);

    QList<CalendarEvent> organizerItems() const override;
    void actionTriggered(const QString &actToken) override;

public slots:
    uint Notify(const QString &app_name, uint replaces_id, const QString &app_icon, const QString &summary, const QString &body, const QStringList &actions, const QVariantHash &hints, int expire_timeout);

private slots:
    void fetchMusicMetadata();
    void mediaPropertiesChanged(const QString &interface, const QVariantMap &changedProps, const QStringList &invalidatedProps);
    void onActiveVoiceCallChanged();
    void onActiveVoiceCallStatusChanged();

private:
    QDBusInterface *m_iface;
    MusicMetaData m_musicMetaData;
    VoiceCallManager *m_voiceCallManager;
    OrganizerAdapter *m_organizerAdapter;
    watchfish::MusicController *m_musicController;
};

#endif // SAILFISHPLATFORM_H
