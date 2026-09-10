/** 省份（直辖市/自治区/特别行政区）列表：添加与查询用 */
export const PROVINCES = [
  '北京', '天津', '河北', '山西', '内蒙古',
  '辽宁', '吉林', '黑龙江',
  '上海', '江苏', '浙江', '安徽', '福建', '江西', '山东',
  '河南', '湖北', '湖南', '广东', '广西', '海南',
  '重庆', '四川', '贵州', '云南', '西藏',
  '陕西', '甘肃', '青海', '宁夏', '新疆',
  '香港', '澳门', '台湾',
]

/** 省份模糊匹配：支持简称（如「广东」匹配「广东省」） */
export function matchProvince(value, keyword) {
  if (!keyword) return true
  return String(value || '').toLowerCase().includes(String(keyword).toLowerCase())
}
