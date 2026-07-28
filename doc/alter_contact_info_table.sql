-- 添加CONTACT_INFO表用于存储联系表单信息
CREATE TABLE `WEB_CONTACT_INFO` (
  `ID` varchar(32) NOT NULL,
  `STATUS` smallint(1) DEFAULT '1',
  `DELETED` smallint(1) DEFAULT '0',
  `CREATE_BY` varchar(50),
  `CREATE_TIME` datetime,
  `UPDATE_BY` varchar(50),
  `UPDATE_TIME` datetime,
  `NAME` varchar(100) NOT NULL COMMENT '联系人姓名',
  `EMAIL` varchar(100) NOT NULL COMMENT '联系人邮箱',
  `SUBJECT` varchar(200) NOT NULL COMMENT '主题',
  `MESSAGE` text NOT NULL COMMENT '留言内容',
  `REQUEST_IP` varchar(50) COMMENT '请求IP地址',
  PRIMARY KEY (`ID`),
  INDEX `IDX_EMAIL` (`EMAIL`),
  INDEX `IDX_CREATE_TIME` (`CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='联系表单信息表';