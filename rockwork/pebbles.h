#ifndef PEBBLES_H
#define PEBBLES_H

#include <QObject>
#include <QAbstractListModel>
#include <QDBusServiceWatcher>
#include <QDBusObjectPath>

class Pebble;
class QDBusInterface;

class Pebbles : public QAbstractListModel
{
    Q_OBJECT
    Q_PROPERTY(bool connectedToService READ connectedToService NOTIFY connectedToServiceChanged)
    Q_PROPERTY(QString version READ version)
    Q_PROPERTY(int count READ rowCount NOTIFY countChanged)
    Q_PROPERTY(bool scanning READ scanning NOTIFY scanningChanged)
    Q_PROPERTY(QVariantList scanResults READ scanResults NOTIFY scanResultsChanged)
public:
    enum Roles {
        RoleAddress,
        RoleName,
        RoleSerialNumber,
        RoleConnected,
        RoleConnectionState
    };

    Pebbles(QObject *parent = 0);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    bool connectedToService();
    QString version() const;

    Q_INVOKABLE Pebble *get(int index) const;
    int find(const QString &address) const;

    bool scanning() const;
    QVariantList scanResults() const;

    // BLE pairing goes through the daemon (org.rockwork.Manager scan API),
    // not the system Bluetooth settings.
    Q_INVOKABLE void startScan();
    Q_INVOKABLE void stopScan();
    Q_INVOKABLE void connectWatch(const QString &address);
    // Stop reconnecting but stay paired. Only takes effect while the watch is connected or
    // attempting to; an idle known watch has to be forgotten instead.
    Q_INVOKABLE void disconnectWatch(const QString &address);
    // Unpair: the daemon keeps retrying a watch forever until it is told to forget it.
    Q_INVOKABLE void forgetWatch(const QString &address);

signals:
    void connectedToServiceChanged();
    void countChanged();
    void scanningChanged();
    void scanResultsChanged();

private slots:
    void refresh();

    void pebbleConnectedChanged();
    void onScanningChanged(bool scanning);
    void refreshScanResults();

private:
    int find(const QDBusObjectPath &path) const;
    static bool sortPebbles(Pebble *a, Pebble *b);

private:
    bool m_connectedToService = false;
    QList<Pebble*> m_pebbles;
    QDBusServiceWatcher *m_watcher;
    bool m_scanning = false;
    QVariantList m_scanResults;
};

#endif // PEBBLES_H
