$(document).ready(function() {
layui.use(['form'], function() {
	var form = layui.form;
	
	/*$("#newBtn").click(function() {
		$.ajax({
			type : 'post',
			url : '/doc/new',
			success : function(resp) {
				if (resp.code == 0) {
					window.location = "/doc/" + resp.data.code;
				} else {
					layer.msg(resp.msg);
				}
			}
		});
	});*/
	
	$(document).on('click', "#newBtn", function() {
		layer.open({
			type : 1,
			title: '新增文档',
			// area: ['420px', '240px'], // 宽高
			content : '<div class="layui-form itemWin">' +
						'<div class="layui-form-item"><input type="text" id="docName" value="新文档" placeholder="请输入文档名称" class="layui-input"/></div>' + 
						'<div class="layui-form-item"><input type="checkbox" id="docStatus" name="docStatus" title="&nbsp;&nbsp;公开|隐藏&nbsp;&nbsp;" lay-skin="switch" lay-filter="docStatus"' + (status == 1?'checked':'') + '></div>' +
						'</div>' + 
						'<script>layui.use(["form"], function(){var form = layui.form;form.render();});</script>',
			btn: ['保存', '取消'],
			btn1: function() {
				var statusVal = $("#docStatus").is(':checked')? 1:0;
				$.ajax({
					type : 'post',
					url : '/doc/save',
					contentType : 'application/json;charset=UTF-8',
					data : JSON.stringify({
						name : $('#docName').val(),
						status: statusVal
					}),
					success : function(resp) {
						if (resp.code == 0) {
							window.location.reload();
						} else {
							layer.msg(resp.msg);
						}
					}
				});
			}
		});
	});
	
	$(document).on('click', "#editBtn", function() {
		var id = $(this).val();
		var name = $(this).parent().children("input[name='name']").val();
		var status = $(this).parent().children("input[name='status']").val();
		renameDoc(id, name, status);
	});
	
	$(document).on('click', ".editBtn", function() {
		var id = $(this).val();
		var name = $(this).parent().children("input[name='name']").val();
		var status = $(this).parent().children("input[name='status']").val();
		renameDoc(id, name, status);
	});
	
	function renameDoc(id, name, status) {
		layer.open({
			type : 1,
			title: '编辑文档',
			// area: ['420px', '240px'], // 宽高
			content : '<div class="layui-form itemWin">' +
						'<div class="layui-form-item"><input type="text" id="docName" value="' + name + '" placeholder="请输入文档名称" class="layui-input"/></div>' + 
						'<div class="layui-form-item"><input type="checkbox" id="docStatus" name="docStatus" title="&nbsp;&nbsp;公开|隐藏&nbsp;&nbsp;" lay-skin="switch" lay-filter="docStatus"' + (status == 1?'checked':'') + '></div>' +
						'</div>' + 
						'<script>layui.use(["form"], function(){var form = layui.form;form.render();});</script>',
			btn: ['保存', '取消'],
			btn1: function() {
				var statusVal = $("#docStatus").is(':checked')? 1:0;
				$.ajax({
					type : 'post',
					url : '/doc/save',
					contentType : 'application/json;charset=UTF-8',
					data : JSON.stringify({
						id : id,
						name : $('#docName').val(),
						status: statusVal
					}),
					success : function(resp) {
						if (resp.code == 0) {
							window.location.reload();
						} else {
							layer.msg(resp.msg);
						}
					}
				});
			}
		});
	}
	
	$(document).on('click', ".delBtn", function() {
		var id = $(this).val();
		var name = $(this).parent().children("input[name='name']").val();
		layer.confirm('确认删除[' + name + ']？', {
			btn : [ '确认', '取消' ]
		// 按钮
		}, function() {
			$.ajax({
				type : 'post',
				url : '/doc/delete',
				contentType : 'application/json;charset=UTF-8',
				data : JSON.stringify({
					id : id
				}),
				success : function(resp) {
					if (resp.code == 0) {
						window.location.reload();
					} else {
						layer.msg(resp.msg);
					}
				}
			});
		});
	});

	$(document).on('click', "#newItemBtn", function() {
		layer.open({
			type : 1,
			title: '新增',
			// area: ['420px', '240px'], // 宽高
			content : '<div class="itemWin">' +
						'<div class="layui-form-item"><input type="text" id="newName" name="newName" value="" placeholder="请输入名称" class="layui-input"/></div>' + 
						'<div class="layui-form-item"><input type="text" id="newLink" name="newLink" value="" placeholder="请输入链接" class="layui-input"/></div>' + 
						'</div>',
			btn: ['保存', '取消'],
			btn1: function() {
				$.ajax({
					type : 'post',
					url : '/doc/item/save',
					contentType : 'application/json;charset=UTF-8',
					data : JSON.stringify({
						documentId : $('#documentId').val(),
						name : $('#newName').val(),
						link : $('#newLink').val()
					}),
					success : function(resp) {
						if (resp.code == 0) {
							window.location.reload();
						} else {
							layer.msg(resp.msg);
						}
					}
				});
			}
		});
	});

	$(document).on('click', "#saveItemBtn", function() {
		$.ajax({
			type : 'post',
			url : '/doc/item/save',
			contentType : 'application/json;charset=UTF-8',
			data : JSON.stringify({
				documentId : $('#documentId').val(),
				name : $('#newName').val(),
				link : $('#newLink').val()
			}),
			success : function(resp) {
				if (resp.code == 0) {
					window.location.reload();
				} else {
					layer.msg(resp.msg);
				}
			}
		});
	});

	$(document).on('click', ".editItemBtn", function() {
		var id = $(this).val();
		var name = $(this).siblings(".link").children("a").attr("title");
		var link = $(this).siblings(".link").children("a").attr("href");
		layer.open({
			type : 1,
			title: '编辑',
			// area: ['420px', '240px'], // 宽高
			content : '<div class="itemWin">' +
						'<div class="layui-form-item"><input type="text" id="editName" value="' + name + '" placeholder="请输入名称" class="layui-input"/></div>' + 
						'<div class="layui-form-item"><input type="text" id="editLink" value="' + link + '" placeholder="请输入链接" class="layui-input"/></div>' + 
						'</div>',
			btn: ['保存', '取消'],
			btn1: function() {
				$.ajax({
					type : 'post',
					url : '/doc/item/save',
					contentType : 'application/json;charset=UTF-8',
					data : JSON.stringify({
						id : id,
						name : $('#editName').val(),
						link : $('#editLink').val()
					}),
					success : function(resp) {
						if (resp.code == 0) {
							window.location.reload();
						} else {
							layer.msg(resp.msg);
						}
					}
				});
			}
		});
	});

	$(document).on('click', ".delItemBtn", function() {
		var id = $(this).val();
		layer.confirm('确认删除？', {
			btn : [ '确认', '取消' ]
		// 按钮
		}, function() {
			$.ajax({
				type : 'post',
				url : '/doc/item/delete',
				contentType : 'application/json;charset=UTF-8',
				data : JSON.stringify({
					id : id
				}),
				success : function(resp) {
					if (resp.code == 0) {
						window.location.reload();
					} else {
						layer.msg(resp.msg);
					}
				}
			});
		});
	});
	
	$(document).on('click', ".upItemBtn", function() {
		var id = $(this).val();
		$.ajax({
			type : 'post',
			url : '/doc/item/moveUp',
			contentType : 'application/json;charset=UTF-8',
			data : JSON.stringify({
				id : id
			}),
			success : function(resp) {
				if (resp.code == 0) {
					window.location.reload();
				} else {
					layer.msg(resp.msg);
				}
			}
		});
	});
	
	$(document).on('click', ".downItemBtn", function() {
		var id = $(this).val();
		$.ajax({
			type : 'post',
			url : '/doc/item/moveDown',
			contentType : 'application/json;charset=UTF-8',
			data : JSON.stringify({
				id : id
			}),
			success : function(resp) {
				if (resp.code == 0) {
					window.location.reload();
				} else {
					layer.msg(resp.msg);
				}
			}
		});
	});
	
	
});
});