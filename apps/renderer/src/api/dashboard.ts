/** 契约 §1 `GET /api/v1/dashboard` */
import { http } from './client';
import type { DashboardData } from '@/types/dashboard';

export function getDashboard(): Promise<DashboardData> {
  return http.get<DashboardData>('/dashboard');
}
