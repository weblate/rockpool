Name:       libpebble3d
Version:    %{?ver}%{!?ver:2.0}
Release:    1
Summary:    Pebble watch daemon built on libpebble3
License:    GPLv3 and Apache-2.0
URL:        https://github.com/abranson/rockpool
Requires:   systemd-user-session-targets
# The binary is a self-contained GraalVM native image; rpm's automatic
# dependency scan would demand exact build-host glibc symbol versions.
AutoReqProv: no

%description
Headless Pebble daemon for Sailfish OS, built from libpebble3 (the
Core Devices/Rebble companion-app library) as a GraalVM native image.
BLE only. Exposes rockpoold's org.rockwork session-bus API for the
rockpool UI, plus Sailfish integrations: notifications, MPRIS media
control, mkcal calendar timeline pins, voicecall, and contacts.

%install
mkdir -p %{buildroot}/usr/libexec/libpebble3d
# /out is the native-image build output: the executable plus the JDK
# shim libraries it loads lazily (AWT, needed for screenshots).
cp /out/libpebble3d %{buildroot}/usr/libexec/libpebble3d/
cp /out/*.so %{buildroot}/usr/libexec/libpebble3d/
mkdir -p %{buildroot}/usr/bin
ln -s ../libexec/libpebble3d/libpebble3d %{buildroot}/usr/bin/libpebble3d
install -D -m 0644 %{_sourcedir}/libpebble3d.service \
    %{buildroot}/usr/lib/systemd/user/libpebble3d.service
# bluetoothd experimental APIs: needed to force LE connects on BlueZ < 5.79
# (see the drop-in's comments).
install -D -m 0644 %{_sourcedir}/bluetooth-experimental.conf \
    %{buildroot}/etc/systemd/system/bluetooth.service.d/50-libpebble3d.conf
mkdir -p %{buildroot}/usr/lib/systemd/user/user-session.target.wants
ln -s ../libpebble3d.service \
    %{buildroot}/usr/lib/systemd/user/user-session.target.wants/libpebble3d.service

%post
# setgid privileged: the calendar/contacts databases sit under .../system/privileged/, which has no
# permissions for "other" and is only traversable via that group — and defaultuser is not in it.
# This raises only the effective gid, so anything checking access(2) (File.canRead) still sees the
# real gid and reports false; the code opens instead. Re-applied here because chgrp drops setgid.
chgrp privileged /usr/libexec/libpebble3d/libpebble3d || :
chmod 2755 /usr/libexec/libpebble3d/libpebble3d || :
# apply the bluetoothd experimental-API drop-in
systemctl daemon-reload || :
systemctl try-restart bluetooth.service || :
systemctl-user daemon-reload || :
systemctl-user try-restart libpebble3d.service || :

%preun
if [ "$1" = "0" ]; then
    systemctl-user stop libpebble3d.service || :
fi

%postun
systemctl-user daemon-reload || :
if [ "$1" = "0" ]; then
    systemctl daemon-reload || :
    systemctl try-restart bluetooth.service || :
fi

%files
%defattr(-,root,root,-)
%dir /usr/libexec/libpebble3d
%attr(2755,root,privileged) /usr/libexec/libpebble3d/libpebble3d
/usr/libexec/libpebble3d/*.so
/usr/bin/libpebble3d
/usr/lib/systemd/user/libpebble3d.service
/usr/lib/systemd/user/user-session.target.wants/libpebble3d.service
%dir /etc/systemd/system/bluetooth.service.d
/etc/systemd/system/bluetooth.service.d/50-libpebble3d.conf
