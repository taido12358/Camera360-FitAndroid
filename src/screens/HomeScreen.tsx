import React from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {useAuthStore} from '../store/useAuthStore';
import {Colors} from '../theme/colors';
import Card from '../components/Card';

const QUICK_ACTIONS = [
  {id: '1', label: 'Đặt lịch khám', icon: '📅', color: '#EBF5FB'},
  {id: '2', label: 'Nhắc thuốc', icon: '💊', color: '#EAFAF1'},
  {id: '3', label: 'Kết quả xét nghiệm', icon: '🔬', color: '#FEF9E7'},
  {id: '4', label: 'Tư vấn trực tuyến', icon: '💬', color: '#FDEDEC'},
];

const HEALTH_METRICS = [
  {label: 'Huyết áp', value: '120/80', unit: 'mmHg', status: 'good'},
  {label: 'Nhịp tim', value: '72', unit: 'bpm', status: 'good'},
  {label: 'Đường huyết', value: '95', unit: 'mg/dL', status: 'good'},
  {label: 'Cân nặng', value: '65', unit: 'kg', status: 'normal'},
];

export default function HomeScreen() {
  const user = useAuthStore(state => state.user);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView showsVerticalScrollIndicator={false}>
        {/* Header */}
        <View style={styles.header}>
          <View>
            <Text style={styles.greeting}>Xin chào,</Text>
            <Text style={styles.userName}>{user?.name ?? 'Người dùng'}</Text>
          </View>
          <View style={styles.avatarBox}>
            <Text style={styles.avatarText}>
              {(user?.name ?? 'U')[0].toUpperCase()}
            </Text>
          </View>
        </View>

        {/* Banner */}
        <Card style={styles.banner}>
          <Text style={styles.bannerTitle}>Lịch hẹn sắp tới</Text>
          <Text style={styles.bannerSub}>Thứ 6, 30/05/2026 - 09:00 sáng</Text>
          <Text style={styles.bannerDoctor}>BS. Trần Thị Bình - Tim mạch</Text>
          <View style={styles.bannerBadge}>
            <Text style={styles.bannerBadgeText}>Bệnh viện Bạch Mai</Text>
          </View>
        </Card>

        {/* Quick Actions */}
        <Text style={styles.sectionTitle}>Dịch vụ nhanh</Text>
        <View style={styles.actionsGrid}>
          {QUICK_ACTIONS.map(action => (
            <TouchableOpacity
              key={action.id}
              style={[styles.actionItem, {backgroundColor: action.color}]}
              activeOpacity={0.7}>
              <Text style={styles.actionIcon}>{action.icon}</Text>
              <Text style={styles.actionLabel}>{action.label}</Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Health Metrics */}
        <Text style={styles.sectionTitle}>Chỉ số sức khỏe hôm nay</Text>
        <View style={styles.metricsGrid}>
          {HEALTH_METRICS.map(metric => (
            <Card key={metric.label} style={styles.metricCard}>
              <Text style={styles.metricLabel}>{metric.label}</Text>
              <Text style={styles.metricValue}>{metric.value}</Text>
              <Text style={styles.metricUnit}>{metric.unit}</Text>
              <View
                style={[
                  styles.metricBadge,
                  {backgroundColor: Colors.primaryLight},
                ]}>
                <Text style={styles.metricBadgeText}>Bình thường</Text>
              </View>
            </Card>
          ))}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: Colors.background},
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 12,
  },
  greeting: {fontSize: 14, color: Colors.textSecondary},
  userName: {fontSize: 22, fontWeight: '800', color: Colors.textPrimary},
  avatarBox: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: Colors.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {fontSize: 18, fontWeight: '700', color: Colors.white},
  banner: {
    marginHorizontal: 20,
    marginBottom: 8,
    backgroundColor: Colors.primary,
  },
  bannerTitle: {fontSize: 12, color: Colors.primaryLight, marginBottom: 4},
  bannerSub: {fontSize: 18, fontWeight: '700', color: Colors.white},
  bannerDoctor: {fontSize: 14, color: Colors.primaryLight, marginTop: 4},
  bannerBadge: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 4,
    marginTop: 10,
  },
  bannerBadgeText: {fontSize: 12, color: Colors.white, fontWeight: '600'},
  sectionTitle: {
    fontSize: 17,
    fontWeight: '700',
    color: Colors.textPrimary,
    marginHorizontal: 20,
    marginTop: 20,
    marginBottom: 12,
  },
  actionsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    paddingHorizontal: 16,
    gap: 8,
  },
  actionItem: {
    width: '47%',
    borderRadius: 14,
    padding: 16,
    alignItems: 'center',
    gap: 8,
  },
  actionIcon: {fontSize: 28},
  actionLabel: {
    fontSize: 13,
    fontWeight: '600',
    color: Colors.textPrimary,
    textAlign: 'center',
  },
  metricsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    paddingHorizontal: 16,
    gap: 10,
    paddingBottom: 24,
  },
  metricCard: {width: '47%', alignItems: 'center', gap: 4},
  metricLabel: {fontSize: 12, color: Colors.textSecondary},
  metricValue: {fontSize: 26, fontWeight: '800', color: Colors.primary},
  metricUnit: {fontSize: 12, color: Colors.textSecondary},
  metricBadge: {
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 2,
    marginTop: 4,
  },
  metricBadgeText: {fontSize: 11, color: Colors.primaryDark, fontWeight: '600'},
});
