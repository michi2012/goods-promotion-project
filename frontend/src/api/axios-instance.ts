import axios, { type AxiosRequestConfig } from 'axios'

export const axiosInstanceClient = axios.create({
  baseURL: '/',
})

export const axiosInstance = <T>(config: AxiosRequestConfig): Promise<T> =>
  axiosInstanceClient(config).then((response) => response.data)
