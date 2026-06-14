// DOM Elements
const requestBtn = document.getElementById('requestBtn');
const resultDomain = document.getElementById('resultDomain');

if (requestBtn) {
	requestBtn.addEventListener('click', function(e) {
	    fetch('/domain/' + resultDomain.innerText + '/refresh', {method:'POST'}).then(resp => {
			return resp.json();
		}).then(data => {
			if (data.code == 0) {
				requestBtn.disabled = true;
				requestBtn.style = 'color:#1010104d; border:2px solid #1010104d; background:unset;';
				showNotification('Request has been submitted.');
			}
		});
	});
}


function isValidDomain(domain) {
    // Simple domain validation
    const domainRegex = /^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.)+[a-z]{2,}$/i;
    return domainRegex.test(domain);
}

function showNotification(message, type) {
    // Create notification element
    const notification = document.createElement('div');
    notification.textContent = message;
    notification.style.position = 'fixed';
    notification.style.top = '20px';
    notification.style.right = '20px';
    notification.style.padding = '15px 20px';
    notification.style.borderRadius = 'var(--radius)';
    notification.style.color = 'white';
    notification.style.fontWeight = '600';
    notification.style.zIndex = '1000';
    notification.style.boxShadow = 'var(--shadow)';
    notification.style.transform = 'translateX(150%)';
    notification.style.transition = 'transform 0.3s ease';
    
    // Set background color based on type
    if (type === 'warning') {
        notification.style.background = 'var(--warning)';
    } else if (type === 'error') {
        notification.style.background = 'var(--danger)';
    } else {
        notification.style.background = 'var(--primary)';
    }
    
    // Add to DOM
    document.body.appendChild(notification);
    
    // Animate in
    setTimeout(() => {
        notification.style.transform = 'translateX(0)';
    }, 100);
    
    // Remove after 3 seconds
    setTimeout(() => {
        notification.style.transform = 'translateX(150%)';
        setTimeout(() => {
            document.body.removeChild(notification);
        }, 300);
    }, 3000);
}

function showLoader(text) {
    const loader = document.getElementById('loader');
    if (!loader) return;
    if (text) {
        const textEl = document.getElementById('loaderText');
        if (textEl) textEl.textContent = text;
    }
    loader.classList.add('active');
}

function hideLoader() {
    const loader = document.getElementById('loader');
    if (loader) loader.classList.remove('active');
}

// Functions
async function performSearch() {
    const domainInput = document.getElementById('domainInput');
    const domain = domainInput ? domainInput.value.trim() : '';

    if (!domain) {
        showNotification('Please enter a domain name', 'warning');
        return;
    }

    if (!isValidDomain(domain)) {
        showNotification('Please enter a valid domain name (e.g., example.com)', 'warning');
        return;
    }

    showLoader();

    const resp = await fetch('/domain/' + domain + '/search', {method:'POST'});
    const respData = await resp.json();

    if (respData.code != 0) {
    	showNotification(respData.msg, 'warning');
    	hideLoader();
        return;
    }

    if (respData.data && respData.data.rdapUrl) {
    	try {
    		const resp2 = await fetch(respData.data.rdapUrl, {method:'GET'});
            const resp2Data = await resp2.json();
            if (resp2Data && resp2Data.ldhName && resp2Data.ldhName == domain) {
            	const resp3 = await fetch('/domain/updateText', {method:'POST',body:JSON.stringify(resp2Data)});
            	const resp3Data = await resp3.json();
            	console.log(resp3Data);
            }
    	} catch (e) {
    		console.error(e);
    	}
	}

	hideLoader();
	window.location.href = '/domain/' + domain;
    
    
    /** fetch('/domain/' + domain + '/search', {method:'POST'})
    .then(resp => {
    	return resp.json();
    })
    .then(resp => {
    	console.log(resp);
    	if (resp.data.rdapUrl) {
    		getDomainInfo(resp.data.rdapUrl);
    	} else {
    		window.location.href = '/domain/' + domain;
    	}
    }).catch(err => console.log('Fail to search domain.', err)); **/
    
}

$(document).ready(function() {
	$("div.header .search .go").click(function() {
		var domain = $("div.header .search input").val();
		if (domain === "") {
			return;
		}

		window.location.href = "/d/" + domain;
	});

	$("div.header .search input").bind('keypress', function(event) {
		if (event.keyCode == '13') {
			var domain = $("div.header .search input").val();
			if (domain === "") {
				return;
			}

			window.location.href = "/d/" + domain;
		}
	});

	/*$("#renameBtn").click(function() {
		var id = $(this).val();
		var name = $(this).prev().text();
		layer.open({
			type : 1,
			title: '重命名',
			// area: ['420px', '240px'], // 宽高
			content : '<div class="itemWin">' +
						'<div class="layui-form-item"><input type="text" id="docName" value="' + name + '" placeholder="请输入名称" class="layui-input"/></div>' + 
						'</div>',
			btn: ['保存', '取消'],
			btn1: function() {
				$.ajax({
					type : 'post',
					url : '/doc/rename',
					contentType : 'application/json;charset=UTF-8',
					data : JSON.stringify({
						id : id,
						name : $('#docName').val()
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
	});*/

	/*$(".editItemBtn").click(function() {
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
	});*/

	/*$(".delItemBtn").click(function() {
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
	});*/
	
	/*$(".upItemBtn").click(function() {
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
	
	$(".downItemBtn").click(function() {
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
	});*/
	
	

});

/**
 * ToolCache - localStorage-based cache for tool query results
 * Usage:
 *   ToolCache.set('whois', 'google.com', data);          // store
 *   var cached = ToolCache.get('whois', 'google.com');   // retrieve (null if expired)
 *   ToolCache.clear('whois');                            // clear all for a tool
 */
var ToolCache = (function() {
    var PREFIX = 'wd_cache_';
    var TTL_MS = 10 * 60 * 1000; // 10 minutes

    function _key(tool, query) {
        return PREFIX + tool + '_' + query.toLowerCase().trim();
    }

    return {
        get: function(tool, query) {
            try {
                var raw = localStorage.getItem(_key(tool, query));
                if (!raw) return null;
                var obj = JSON.parse(raw);
                if (Date.now() - obj.ts > TTL_MS) {
                    localStorage.removeItem(_key(tool, query));
                    return null;
                }
                return obj.data;
            } catch(e) { return null; }
        },
        set: function(tool, query, data) {
            try {
                localStorage.setItem(_key(tool, query), JSON.stringify({ ts: Date.now(), data: data }));
            } catch(e) {
                // storage quota exceeded — purge old entries
                try {
                    var keys = Object.keys(localStorage).filter(function(k){ return k.indexOf(PREFIX)===0; });
                    keys.sort().slice(0, Math.ceil(keys.length/2)).forEach(function(k){ localStorage.removeItem(k); });
                    localStorage.setItem(_key(tool, query), JSON.stringify({ ts: Date.now(), data: data }));
                } catch(e2) {}
            }
        },
        clear: function(tool) {
            try {
                var prefix = PREFIX + (tool || '');
                Object.keys(localStorage).filter(function(k){ return k.indexOf(prefix)===0; })
                    .forEach(function(k){ localStorage.removeItem(k); });
            } catch(e) {}
        }
    };
})();