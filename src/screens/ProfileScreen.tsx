import React from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Alert,
} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {useAuthStore} from '../store/useAuthStore';
import {Colors} from '../theme/colors';
import Card from '../components/Card';

const MENU_ITEMS = [
  {id: '1', icon: '👤', label: 'Thông tin cá nhân'},
  {id: '2', icon: '🩺', label: 'Hồ sơ bệnh án'},
  {id: '3', icon: '📊', label: 'Lịch sử khám bệnh'},
  {id: '4', icon: '🔔', label: 'Cài đặt thông báo'},
  {id: '5', icon: '🔒', label: 'Bảo mật & Quyền riêng tư'},
  {id: '6', icon: '❓', label: 'Trợ giúp & Hỗ trợ'},
  {id: '7', icon: '⭐', label: 'Đánh giá ứng dụng'},
];

const HEALTH_INFO = [
  {label: 'Nhóm máu', value: 'A+'},
  {label: 'Chiều cao', value: '168 cm'},
  {label: 'Cân nặng', value: '65 kg'},
  {label: 'Dị ứng', value: 'Penicillin'},
];

export default function ProfileScreen() {
  const user = useAuthStore(state => state.user);
  const logout = useAuthStore(state => state.logout);

  const handleLogout = () => {
    Alert.alert('Đăng xuất', 'Bạn có chắc muốn đăng xuất?', [
      {text: 'Hủy', style: 'cancel'},
      {text: 'Đăng xuất', style: 'destructive', onPress: logout},
    ]);
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false}>
        {/* Profile card */}
        <View style={styles.profileSection}>
          <View style={styles.avatar}>
            <Text style={styles.avatarText}>
              {(user?.name ?? 'U')[0].toUpperCase()}
            </Text>
          </View>
          <Text style={styles.name}>{user?.name}</Text>
          <Text style={styles.phone}>{user?.phone}</Text>
        </View>

        {/* Health info */}
        <Text style={styles.sectionTitle}>Thông tin sức khỏe</Text>
        <Card style={styles.healthCard}>
          {HEALTH_INFO.map((item, idx) => (
            <View key={item.label}>
              <View style={styles.healthRow}>
                <Text style={styles.healthLabel}>{item.label}</Text>
                <Text style={styles.healthValue}>{item.value}</Text>
              </View>
              {idx < HEALTH_INFO.length - 1 && (
                <View style={styles.divider} />
              )}
            </View>
          ))}
        </Card>

        {/* Menu */}
        <Text style={styles.sectionTitle}>Cài đặt</Text>
        <Card style={styles.menuCard}>
          {MENU_ITEMS.map((item, idx) => (
            <View key={item.id}>
              <TouchableOpacity
                style={styles.menuItem}
                onPress={() =>
                  Alert.alert(item.label, 'Chức năng đang phát triển')
                }
                activeOpacity={0.7}>
                <Text style={styles.menuIcon}>{item.icon}</Text>
                <Text style={styles.menuLabel}>{item.label}</Text>
                <Text style={styles.menuArrow}>›</Text>
              </TouchableOpacity>
              {idx < MENU_ITEMS.length - 1 && (
                <View style={styles.divider} />
              )}
            </View>
          ))}
        </Card>

        {/* Logout */}
        <TouchableOpacity style={styles.logoutBtn} onPress={handleLogout}>
          <Text style={styles.logoutText}>Đăng xuất</Text>
        </TouchableOpacity>

        <Text style={styles.version}>GoldenCare v1.0.0</Text>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: Colors.background},
  profileSection: {alignItems: 'center', paddingTop: 32, paddingBottom: 24},
  avatar: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: Colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },
  avatarText: {fontSize: 32, fontWeight: '800', color: Colors.white},
  name: {fontSize: 20, fontWeight: '800', color: Colors.textPrimary},
  phone: {fontSize: 14, color: Colors.textSecondary, marginTop: 4},
  sectionTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: Colors.textPrimary,
    marginHorizontal: 20,
    marginBottom: 12,
  },
  healthCard: {marginHorizontal: 20, marginBottom: 20},
  healthRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 10,
  },
  healthLabel: {fontSize: 14, color: Colors.textSecondary},
  healthValue: {fontSize: 14, fontWeight: '700', color: Colors.textPrimary},
  divider: {height: 1, backgroundColor: Colors.border},
  menuCard: {marginHorizontal: 20, marginBottom: 20, padding: 0, overflow: 'hidden'},
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 14,
    paddingHorizontal: 16,
    gap: 12,
  },
  menuIcon: {fontSize: 18},
  menuLabel: {flex: 1, fontSize: 15, color: Colors.textPrimary},
  menuArrow: {fontSize: 20, color: Colors.textLight},
  logoutBtn: {
    marginHorizontal: 20,
    marginBottom: 12,
    height: 52,
    borderRadius: 12,
    borderWidth: 1.5,
    borderColor: Colors.danger,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: {fontSize: 16, fontWeight: '700', color: Colors.danger},
  version: {
    textAlign: 'center',
    color: Colors.textLight,
    fontSize: 12,
    marginBottom: 32,
  },
});
