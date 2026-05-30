import React, {useState} from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  Switch,
  Alert,
} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {Colors} from '../theme/colors';
import Card from '../components/Card';
import Button from '../components/Button';

interface Medication {
  id: string;
  name: string;
  dose: string;
  schedule: string;
  remaining: number;
  active: boolean;
  color: string;
}

const MEDICATIONS: Medication[] = [
  {
    id: '1',
    name: 'Amlodipine',
    dose: '5mg - 1 viên',
    schedule: 'Sáng sau ăn',
    remaining: 14,
    active: true,
    color: '#EBF5FB',
  },
  {
    id: '2',
    name: 'Metformin',
    dose: '500mg - 2 viên',
    schedule: 'Sáng & tối sau ăn',
    remaining: 28,
    active: true,
    color: '#EAFAF1',
  },
  {
    id: '3',
    name: 'Vitamin D3',
    dose: '1000 IU - 1 viên',
    schedule: 'Trưa sau ăn',
    remaining: 60,
    active: false,
    color: '#FEF9E7',
  },
];

export default function MedicationScreen() {
  const [meds, setMeds] = useState(MEDICATIONS);

  const toggleMed = (id: string, value: boolean) => {
    setMeds(prev =>
      prev.map(m => (m.id === id ? {...m, active: value} : m)),
    );
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Nhắc uống thuốc</Text>
      </View>

      {/* Today summary */}
      <Card style={styles.summaryCard}>
        <Text style={styles.summaryTitle}>Hôm nay, 28/05/2026</Text>
        <View style={styles.summaryRow}>
          <View style={styles.summaryItem}>
            <Text style={styles.summaryNum}>2</Text>
            <Text style={styles.summaryLabel}>Đã uống</Text>
          </View>
          <View style={styles.summaryDivider} />
          <View style={styles.summaryItem}>
            <Text style={[styles.summaryNum, {color: Colors.secondary}]}>1</Text>
            <Text style={styles.summaryLabel}>Chưa uống</Text>
          </View>
          <View style={styles.summaryDivider} />
          <View style={styles.summaryItem}>
            <Text style={styles.summaryNum}>3</Text>
            <Text style={styles.summaryLabel}>Tổng cộng</Text>
          </View>
        </View>
      </Card>

      <Text style={styles.sectionTitle}>Danh sách thuốc</Text>

      <ScrollView showsVerticalScrollIndicator={false} style={styles.list}>
        {meds.map(med => (
          <Card key={med.id} style={[styles.medCard, {borderLeftColor: Colors.primary, borderLeftWidth: 4}]}>
            <View style={styles.medRow}>
              <View style={[styles.medIcon, {backgroundColor: med.color}]}>
                <Text style={styles.medIconText}>💊</Text>
              </View>
              <View style={styles.medInfo}>
                <Text style={styles.medName}>{med.name}</Text>
                <Text style={styles.medDose}>{med.dose}</Text>
                <Text style={styles.medSchedule}>🕐 {med.schedule}</Text>
              </View>
              <Switch
                value={med.active}
                onValueChange={v => toggleMed(med.id, v)}
                trackColor={{false: Colors.border, true: Colors.primaryLight}}
                thumbColor={med.active ? Colors.primary : Colors.textLight}
              />
            </View>
            <View style={styles.medFooter}>
              <Text style={styles.remaining}>
                Còn lại:{' '}
                <Text
                  style={{
                    color:
                      med.remaining < 10 ? Colors.danger : Colors.textPrimary,
                    fontWeight: '700',
                  }}>
                  {med.remaining} viên
                </Text>
              </Text>
              {med.remaining < 15 && (
                <Text style={styles.lowStock}>⚠️ Sắp hết thuốc</Text>
              )}
            </View>
          </Card>
        ))}

        <Button
          title="+ Thêm thuốc mới"
          variant="outline"
          onPress={() => Alert.alert('Thêm thuốc', 'Chức năng đang phát triển')}
          style={styles.addBtn}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: Colors.background},
  header: {padding: 20, paddingBottom: 8},
  title: {fontSize: 24, fontWeight: '800', color: Colors.textPrimary},
  summaryCard: {marginHorizontal: 20, marginBottom: 4},
  summaryTitle: {fontSize: 13, color: Colors.textSecondary, marginBottom: 12},
  summaryRow: {flexDirection: 'row', justifyContent: 'space-around'},
  summaryItem: {alignItems: 'center'},
  summaryNum: {fontSize: 28, fontWeight: '800', color: Colors.success},
  summaryLabel: {fontSize: 12, color: Colors.textSecondary, marginTop: 2},
  summaryDivider: {width: 1, backgroundColor: Colors.border, marginVertical: 4},
  sectionTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: Colors.textPrimary,
    marginHorizontal: 20,
    marginTop: 20,
    marginBottom: 12,
  },
  list: {paddingHorizontal: 20},
  medCard: {marginBottom: 12},
  medRow: {flexDirection: 'row', alignItems: 'center', gap: 12},
  medIcon: {
    width: 48,
    height: 48,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  medIconText: {fontSize: 22},
  medInfo: {flex: 1},
  medName: {fontSize: 16, fontWeight: '700', color: Colors.textPrimary},
  medDose: {fontSize: 13, color: Colors.textSecondary, marginTop: 2},
  medSchedule: {fontSize: 12, color: Colors.textSecondary, marginTop: 2},
  medFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: Colors.border,
  },
  remaining: {fontSize: 13, color: Colors.textSecondary},
  lowStock: {fontSize: 12, color: Colors.danger, fontWeight: '600'},
  addBtn: {marginBottom: 24},
});
