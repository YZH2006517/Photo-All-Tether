package org.photoall.tether.ptp

/** PTP (ISO 15740) codes used here, with Nikon's vendor extension. */
object Ptp {
    // container types
    const val COMMAND = 1
    const val DATA = 2
    const val RESPONSE = 3
    const val EVENT = 4

    // operations
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_STORAGE_INFO = 0x1005
    const val GET_NUM_OBJECTS = 0x1006
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val DELETE_OBJECT = 0x100B
    const val INITIATE_CAPTURE = 0x100E
    const val SET_OBJECT_PROTECTION = 0x1012
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
    const val GET_PARTIAL_OBJECT = 0x101B
    const val NIKON_GET_LARGE_THUMB = 0x90C4
    const val NIKON_GET_EVENT = 0x90C7
    const val NIKON_DEVICE_READY = 0x90C8

    // responses
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val OBJECT_WRITE_PROTECTED = 0x200D
    const val ACCESS_DENIED = 0x200F
    const val NO_THUMBNAIL = 0x2010
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201E

    // events
    const val EVENT_OBJECT_ADDED = 0x4002
    const val EVENT_OBJECT_REMOVED = 0x4003
    const val EVENT_STORE_ADDED = 0x4004
    const val EVENT_STORE_REMOVED = 0x4005
    const val EVENT_DEVICE_PROP_CHANGED = 0x4006
    const val EVENT_OBJECT_INFO_CHANGED = 0x4007
    const val EVENT_STORE_FULL = 0x400A
    const val EVENT_STORAGE_INFO_CHANGED = 0x400C
    const val EVENT_CAPTURE_COMPLETE = 0x400D
    const val NIKON_EVENT_OBJECT_ADDED_IN_SDRAM = 0xC101
    const val NIKON_EVENT_CAPTURE_COMPLETE_IN_SDRAM = 0xC102

    // object formats
    const val FORMAT_UNDEFINED = 0x3000
    const val FORMAT_ASSOCIATION = 0x3001
    const val FORMAT_EXIF_JPEG = 0x3801
    const val FORMAT_TIFF = 0x380D

    // device properties
    const val PROP_BATTERY_LEVEL = 0x5001
    const val PROP_WHITE_BALANCE = 0x5005
    const val PROP_F_NUMBER = 0x5007
    const val PROP_FOCAL_LENGTH = 0x5008
    const val PROP_EXPOSURE_TIME = 0x500D
    const val PROP_EXPOSURE_PROGRAM = 0x500E
    const val PROP_EXPOSURE_INDEX = 0x500F
    const val PROP_EXPOSURE_BIAS = 0x5010

    /** Nikon: the handle of a picture held in the camera's memory (not on a card). */
    const val NIKON_SDRAM_HANDLE = 0xFFFF0001.toInt()

    fun responseName(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "一般错误"
        SESSION_NOT_OPEN -> "会话未打开"
        0x2004 -> "事务号无效"
        OPERATION_NOT_SUPPORTED -> "相机不支持此操作"
        0x2006 -> "参数不支持"
        0x2007 -> "传输不完整"
        0x2008 -> "存储卡 ID 无效"
        INVALID_OBJECT_HANDLE -> "文件句柄无效（可能已被删除）"
        0x200A -> "相机不支持此属性"
        0x200C -> "存储卡已满"
        OBJECT_WRITE_PROTECTED -> "文件受保护"
        0x200E -> "存储卡只读"
        ACCESS_DENIED -> "拒绝访问"
        NO_THUMBNAIL -> "没有缩略图"
        0x2013 -> "存储卡不可用"
        DEVICE_BUSY -> "相机忙"
        SESSION_ALREADY_OPEN -> "会话已打开"
        0x201F -> "事务已取消"
        0xA001 -> "相机硬件错误"
        0xA002 -> "未合焦"
        else -> "0x%04X".format(code)
    }

    fun operationName(code: Int): String = when (code) {
        GET_DEVICE_INFO -> "GetDeviceInfo"
        OPEN_SESSION -> "OpenSession"
        CLOSE_SESSION -> "CloseSession"
        GET_STORAGE_IDS -> "GetStorageIDs"
        GET_STORAGE_INFO -> "GetStorageInfo"
        GET_NUM_OBJECTS -> "GetNumObjects"
        GET_OBJECT_HANDLES -> "GetObjectHandles"
        GET_OBJECT_INFO -> "GetObjectInfo"
        GET_OBJECT -> "GetObject"
        GET_THUMB -> "GetThumb"
        DELETE_OBJECT -> "DeleteObject"
        INITIATE_CAPTURE -> "InitiateCapture"
        SET_OBJECT_PROTECTION -> "SetObjectProtection"
        GET_DEVICE_PROP_VALUE -> "GetDevicePropValue"
        SET_DEVICE_PROP_VALUE -> "SetDevicePropValue"
        GET_PARTIAL_OBJECT -> "GetPartialObject"
        NIKON_GET_LARGE_THUMB -> "Nikon GetLargeThumb"
        NIKON_GET_EVENT -> "Nikon GetEvent"
        NIKON_DEVICE_READY -> "Nikon DeviceReady"
        else -> "0x%04X".format(code)
    }

    fun eventName(code: Int): String = when (code) {
        EVENT_OBJECT_ADDED -> "ObjectAdded"
        EVENT_OBJECT_REMOVED -> "ObjectRemoved"
        EVENT_STORE_ADDED -> "StoreAdded"
        EVENT_STORE_REMOVED -> "StoreRemoved"
        EVENT_DEVICE_PROP_CHANGED -> "DevicePropChanged"
        EVENT_OBJECT_INFO_CHANGED -> "ObjectInfoChanged"
        EVENT_STORE_FULL -> "StoreFull"
        EVENT_STORAGE_INFO_CHANGED -> "StorageInfoChanged"
        EVENT_CAPTURE_COMPLETE -> "CaptureComplete"
        NIKON_EVENT_OBJECT_ADDED_IN_SDRAM -> "Nikon ObjectAddedInSdram"
        NIKON_EVENT_CAPTURE_COMPLETE_IN_SDRAM -> "Nikon CaptureCompleteInSdram"
        else -> "0x%04X".format(code)
    }
}

/** A PTP operation answered with anything but OK. */
class PtpException(val operation: Int, val code: Int) :
    Exception("${Ptp.operationName(operation)}：${Ptp.responseName(code)}")
