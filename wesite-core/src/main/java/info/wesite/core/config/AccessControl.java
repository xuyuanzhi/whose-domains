package info.wesite.core.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标示访问控制
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.METHOD})
public @interface AccessControl
{
    Level level() default Level.NONE;

    /**
     * 控制级别枚举
     */
    enum Level
    {
        /**
         * 无访问控制
         */
        NONE((byte) 0),

        /**
         * 会话访问控制
         */
        SESSION((byte) 1);

        /**
         * 授权访问控制，用于控制访问权限，目前未使用
         */
        //AUTH((byte) 2);

        private byte code;

        Level(byte code)
        {
            this.code = code;
        }

        public byte getCode()
        {
            return code;
        }
    }
}
