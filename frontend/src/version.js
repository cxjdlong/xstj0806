/* 构建版本标识：用于确认用户手上是不是最新那份 html */
export const APP_VERSION = '1.1'
export const BUILD_TIME = (typeof __BUILD_TIME__ !== 'undefined') ? __BUILD_TIME__ : 'dev'
export const DB_FILE_NAME = 'contacts.db'
