Name:       rockpool

%{!?qtc_qmake:%define qtc_qmake %qmake}
%{!?qtc_qmake5:%define qtc_qmake5 %qmake5}
%{!?qtc_make:%define qtc_make make}
%{?qtc_builddir:%define _builddir %qtc_builddir}
Summary:    Support for Pebble watches in SailfishOS
Version:    2.0
Release:    1
Group:      Qt/Qt
License:    GPLv3
URL:        http://getpebble.com/
Source0:    %{name}-%{version}.tar.xz
# The UI only; the watch daemon is the libpebble3d package.
Requires:   libpebble3d
BuildRequires:  pkgconfig(Qt5DBus)
BuildRequires:  pkgconfig(Qt5Quick)
BuildRequires:  pkgconfig(Qt5Qml)
BuildRequires:  pkgconfig(Qt5Core)
BuildRequires:  pkgconfig(Qt5Network)
BuildRequires:  pkgconfig(sailfishapp) >= 0.0.10
BuildRequires:  pkgconfig(sailfishwebengine)
BuildRequires:  pkgconfig(qt5embedwidget)
BuildRequires:  desktop-file-utils
BuildRequires:  qt5-qttools-linguist

%description
Support for Pebble watches on SailfishOS devices. This package contains
the Silica UI; the daemon it talks to (org.rockwork) is provided by
libpebble3d.

%prep
%setup -q -n %{name}-%{version}

%build
mkdir -p build
cd build
%qmake5  \
    DEFINES+=VERSION=\\\'\\\"%{version}-%{release}\\\"\\\' \
    INSTALL_DIR=%{install_dir} \
    ../rockwork/rockwork.pro

%qtc_make %{?_smp_mflags}

%install
rm -rf %{buildroot}
cd build
%qmake5_install

# Docker volume mounts can drop the execute bit on the installed binary.
chmod 0755 %{buildroot}%{_bindir}/rockpool

desktop-file-install --delete-original       \
  --dir %{buildroot}%{_datadir}/applications             \
   %{buildroot}%{_datadir}/applications/*.desktop

%post
update-desktop-database

%files
%defattr(-,root,root,-)
%{_bindir}/rockpool
%{_datadir}/%{name}/qml
%{_datadir}/%{name}/jsm
%{_datadir}/%{name}/translations
%{_datadir}/applications/%{name}.desktop
%{_datadir}/icons/hicolor/86x86/apps/%{name}.png
%{_datadir}/icons/hicolor/108x108/apps/%{name}.png
%{_datadir}/icons/hicolor/128x128/apps/%{name}.png
%{_datadir}/icons/hicolor/256x256/apps/%{name}.png
%{_sysconfdir}/sailjail/permissions/Rockpool.permission
%{_sysconfdir}/sailjail/permissions/rockpool.profile
