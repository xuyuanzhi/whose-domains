package info.wesite.admin.view;

import lombok.Data;

@Data
public class SearchParam {

	private String keyword;
	
	private Integer page;
	
	private Integer limit;
}
