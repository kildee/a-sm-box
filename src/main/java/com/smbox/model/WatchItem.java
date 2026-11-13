package com.smbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchItem {
    /** 所属分组名 */
    private String groupName;
    /** 6 位代码 */
    private String code;
    /** 名称 */
    private String name;
    private String secid;
    /** 排序序号（越小越靠前） */
    private int sortOrder;
}
