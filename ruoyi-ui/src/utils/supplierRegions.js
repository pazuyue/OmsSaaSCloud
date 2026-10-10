import { areaList } from '@vant/area-data'

// Persist existing address names, while deriving choices from a fixed national catalog.
const provinces = Object.entries(areaList.province_list)
const cities = Object.entries(areaList.city_list)
const counties = Object.entries(areaList.county_list)
const withCurrent = (names, current) => [...new Set([...names, current].filter(Boolean))]

export function supplierRegionOptions(field, address) {
  if (field === 'contactProvince') {
    return withCurrent(provinces.map(([, name]) => name), address.contactProvince)
  }
  if (!address.contactProvince) return []
  const province = provinces.find(([, name]) => name === address.contactProvince)
  const provinceCities = province ? cities.filter(([code]) => code.slice(0, 2) === province[0].slice(0, 2)) : []
  if (field === 'contactCity') {
    return withCurrent(provinceCities.map(([, name]) => name), address.contactCity)
  }
  if (field !== 'contactArea' || !address.contactCity) return []
  const city = provinceCities.find(([, name]) => name === address.contactCity)
  return withCurrent(city ? counties.filter(([code]) => code.slice(0, 4) === city[0].slice(0, 4)).map(([, name]) => name) : [], address.contactArea)
}
