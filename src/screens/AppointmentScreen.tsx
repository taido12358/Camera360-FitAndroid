import React, {useState} from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Alert,
} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {Colors} from '../theme/colors';
import Card from '../components/Card';
import Button from '../components/Button';

const APPOINTMENTS = [
  {
    id: '1',
    doctor: 'BS. Nguyễn Minh Tuấn',
    specialty: 'Tim mạch',
    hospital: 'BV Bạch Mai',
    date: 'Thứ 6, 30/05/2026',
    time: '09:00 sáng',
    status: 'upcoming',
  },
  {
    id: '2',
    doctor: 'BS. Lê Thị Hương',
    specialty: 'Nội tiết',
    hospital: 'BV Việt Đức',
    date: 'Thứ 2, 03/06/2026',
    time: '02:30 chiều',
    status: 'upcoming',
  },
  {
    id: '3',
    doctor: 'BS. Phạm Văn Đức',
    specialty: 'Tổng quát',
    hospital: 'Phòng khám MEDCARE',
    date: 'Thứ 4, 15/05/2026',
    time: '10:00 sáng',
    status: 'done',
  },
];

const STATUS_LABEL: Record<string, {text: string; color: string}> = {
  upcoming: {text: 'Sắp tới', color: Colors.primary},
  done: {text: 'Đã khám', color: Colors.success},
  cancelled: {text: 'Đã hủy', color: Colors.danger},
};

export default function AppointmentScreen() {
  const [tab, setTab] = useState<'upcoming' | 'done'>('upcoming');

  const filtered = APPOINTMENTS.filter(a =>
    tab === 'upcoming' ? a.status === 'upcoming' : a.status === 'done',
  );

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Lịch hẹn khám</Text>
      </View>

      {/* Tabs */}
      <View style={styles.tabs}>
        <TouchableOpacity
          style={[styles.tab, tab === 'upcoming' && styles.tabActive]}
          onPress={() => setTab('upcoming')}>
          <Text
            style={[styles.tabText, tab === 'upcoming' && styles.tabTextActive]}>
            Sắp tới
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.tab, tab === 'done' && styles.tabActive]}
          onPress={() => setTab('done')}>
          <Text style={[styles.tabText, tab === 'done' && styles.tabTextActive]}>
            Đã khám
          </Text>
        </TouchableOpacity>
      </View>

      <ScrollView showsVerticalScrollIndicator={false} style={styles.list}>
        {filtered.map(appt => {
          const badge = STATUS_LABEL[appt.status];
          return (
            <Card key={appt.id} style={styles.card}>
              <View style={styles.cardRow}>
                <View style={styles.cardLeft}>
                  <Text style={styles.doctor}>{appt.doctor}</Text>
                  <Text style={styles.specialty}>{appt.specialty}</Text>
                </View>
                <View style={[styles.badge, {borderColor: badge.color}]}>
                  <Text style={[styles.badgeText, {color: badge.color}]}>
                    {badge.text}
                  </Text>
                </View>
              </View>
              <View style={styles.divider} />
              <Text style={styles.info}>🏥 {appt.hospital}</Text>
              <Text style={styles.info}>📅 {appt.date}</Text>
              <Text style={styles.info}>🕐 {appt.time}</Text>
              {appt.status === 'upcoming' && (
                <View style={styles.actions}>
                  <Button
                    title="Hủy lịch"
                    variant="outline"
                    onPress={() =>
                      Alert.alert('Xác nhận', 'Bạn muốn hủy lịch hẹn này?', [
                        {text: 'Không'},
                        {text: 'Hủy lịch', style: 'destructive'},
                      ])
                    }
                    style={styles.btnCancel}
                  />
                  <Button
                    title="Chi tiết"
                    onPress={() =>
                      Alert.alert('Chi tiết', 'Chức năng đang phát triển')
                    }
                    style={styles.btnDetail}
                  />
                </View>
              )}
            </Card>
          );
        })}
      </ScrollView>

      <View style={styles.fab}>
        <Button
          title="+ Đặt lịch mới"
          onPress={() => Alert.alert('Đặt lịch', 'Chức năng đang phát triển')}
        />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: Colors.background},
  header: {padding: 20, paddingBottom: 8},
  title: {fontSize: 24, fontWeight: '800', color: Colors.textPrimary},
  tabs: {
    flexDirection: 'row',
    marginHorizontal: 20,
    backgroundColor: Colors.border,
    borderRadius: 10,
    padding: 3,
    marginBottom: 16,
  },
  tab: {flex: 1, paddingVertical: 8, borderRadius: 8, alignItems: 'center'},
  tabActive: {backgroundColor: Colors.white},
  tabText: {fontSize: 14, fontWeight: '600', color: Colors.textSecondary},
  tabTextActive: {color: Colors.primary},
  list: {paddingHorizontal: 20},
  card: {marginBottom: 12},
  cardRow: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start'},
  cardLeft: {flex: 1},
  doctor: {fontSize: 16, fontWeight: '700', color: Colors.textPrimary},
  specialty: {fontSize: 13, color: Colors.textSecondary, marginTop: 2},
  badge: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  badgeText: {fontSize: 12, fontWeight: '600'},
  divider: {height: 1, backgroundColor: Colors.border, marginVertical: 12},
  info: {fontSize: 13, color: Colors.textSecondary, marginBottom: 4},
  actions: {flexDirection: 'row', gap: 8, marginTop: 12},
  btnCancel: {flex: 1, height: 40},
  btnDetail: {flex: 1, height: 40},
  fab: {padding: 20, paddingTop: 8},
});
