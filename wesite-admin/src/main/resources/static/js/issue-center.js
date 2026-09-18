(function () {
    'use strict';
    var statuses = {open:'待处理',in_progress:'处理中',resolved:'已解决',ignored:'已忽略'};
    var sources = {server_error:'服务端异常',client_error:'脚本异常',request_failure:'请求失败',task_error:'任务异常'};
    function node(tag, text, css) { var el=document.createElement(tag); if(text!=null)el.textContent=String(text); if(css)el.className=css; return el; }
    function time(value) { return value?new Date(Number(value)).toLocaleString():'—'; }
    function mount(root, admin) {
        if (!root || root.dataset.mounted) return; root.dataset.mounted='true';
        var page=1, listSeq=0, detailSeq=0, selected=null, saving=false, detail=null, previousFocus=null;
        var drawer=root.querySelector('.issue-detail'), message=root.querySelector('[data-message]'), body=root.querySelector('tbody');
        var detailError=root.querySelector('[data-detail-error]'), detailBody=root.querySelector('[data-detail-body]');
        function request(path, data) {
            return new Promise(function(resolve,reject){
                admin.req({timeout:15000,url:'/admin/issues'+path,type:data?'post':'get',contentType:'application/json',data:data?JSON.stringify(data):undefined,
                    success:function(resp){ if(resp && resp.code===0)resolve(resp.data); else reject(new Error(resp && resp.code===401?'登录已过期，请重新登录。':'请求失败，请刷新重试。')); },
                    error:function(xhr){reject(new Error(xhr.status===409?'问题已更新，请刷新详情后重新保存。':xhr.status===401?'登录已过期，请重新登录。':xhr.status===403?'当前账户没有权限。':'请求失败，请刷新重试。'));}
                });
            });
        }
        function pager(target,current,total,change) {
            target.replaceChildren();
            var prev=node('button','上一页','layui-btn layui-btn-primary layui-btn-sm'),next=node('button','下一页','layui-btn layui-btn-primary layui-btn-sm');
            prev.disabled=current<=1; next.disabled=current*20>=total;
            prev.onclick=function(){change(current-1);}; next.onclick=function(){change(current+1);};
            target.append(prev,node('span','第 '+current+' 页 · 共 '+total+' 条'),next);
        }
        async function load() {
            var seq=++listSeq; message.textContent='正在加载…';message.className='issue-muted';body.replaceChildren();root.querySelector('[data-pager]').replaceChildren();
            var params=new URLSearchParams(new FormData(root.querySelector('form')));params.set('page',page);params.set('size',20);
            ['from','to'].forEach(function(key){var v=params.get(key);if(v)params.set(key, new Date(v+'T00:00:00').getTime()+(key==='to'?86400000-1:0));else params.delete(key);});
            try {
                var data=await request('/list?'+params);
                if(seq!==listSeq || !root.isConnected)return;
                if(page>1 && !data.items.length && data.total<(page-1)*20+1){page=Math.max(1,Math.ceil(data.total/20));return load();}
                root.querySelector('[data-environment]').textContent='环境：'+data.environment+(data.enabled?'':' · 采集已关闭');
                message.textContent=data.items.length?'':'当前筛选下暂无问题。';
                data.items.forEach(function(issue){
                    var row=node('tr'),title=node('td'),button=node('button',issue.summary);
                    button.onclick=function(){open(issue.id,button);}; title.append(button,node('div',issue.route,'issue-muted'));
                    row.append(title,node('td',(issue.app==='web'?'网站':'后台')+' / '+(sources[issue.source]||issue.source)),node('td',statuses[issue.status]),node('td',issue.occurrence_count),node('td',time(issue.first_seen_at)),node('td',time(issue.last_seen_at)));body.append(row);
                });
                pager(root.querySelector('[data-pager]'),page,data.total,function(p){page=p;load();});
            }catch(error){if(seq===listSeq && root.isConnected){message.textContent=error.message;message.className='issue-error';}}
        }
        function close() { if(saving)return;selected=null;detailSeq++;drawer.hidden=true;detailBody.replaceChildren();if(previousFocus && previousFocus.isConnected)previousFocus.focus(); }
        async function open(id,focus) {
            if(saving)return;
            if(focus)previousFocus=focus;
            selected=id;var seq=++detailSeq;drawer.hidden=false;drawer.focus();detail=null;detailBody.replaceChildren();detailError.textContent='正在加载…';
            try { var result=await request('/'+encodeURIComponent(id));if(seq!==detailSeq || !root.isConnected)return;detail=result;detailError.textContent='';renderDetail(seq); }
            catch(error){if(seq===detailSeq)detailError.textContent=error.message;}
        }
        function renderDetail(seq) {
            detailBody.replaceChildren();
            detailBody.append(node('h3',detail.summary),node('p','累计 '+detail.occurrence_count+' 次 · 首次 '+time(detail.first_seen_at)+' · 最近 '+time(detail.last_seen_at),'issue-muted'),node('p','最近版本：'+detail.last_release,'issue-muted'));
            var form=node('form'),statusLabel=node('label','处理状态 '),select=node('select');
            Object.keys(statuses).forEach(function(key){var option=node('option',statuses[key]);option.value=key;select.append(option);});select.value=detail.status;statusLabel.append(select);
            var releaseLabel=node('label','修复版本 '),release=node('input');release.value=detail.resolved_release||'';release.maxLength=64;release.pattern='[a-zA-Z0-9.:_+\\-]*';releaseLabel.append(release);
            var noteLabel=node('label','新增处理备注'),note=node('textarea');note.maxLength=1000;noteLabel.append(note);
            var save=node('button','保存处理记录','layui-btn layui-btn-sm');form.append(statusLabel,releaseLabel,noteLabel,save);detailBody.append(form);
            form.onsubmit=async function(event){
                event.preventDefault();if(saving || seq!==detailSeq)return;
                var target=selected;saving=true;save.disabled=true;select.disabled=true;release.disabled=true;note.disabled=true;detailError.textContent='';
                try {await request('/'+encodeURIComponent(target)+'/status',{status:select.value,note:note.value,resolvedRelease:release.value,version:detail.version});saving=false;load();await open(target);}
                catch(error){if(seq===detailSeq)detailError.textContent=error.message;}
                finally {saving=false;save.disabled=false;select.disabled=false;release.disabled=false;note.disabled=false;}
            };
            section('events','事件明细',seq);section('notes','处理记录',seq);
        }
        function section(kind,title,seq) {
            var container=node('section'),heading=node('h3',title),status=node('p'),rows=node('div'),pages=node('div',null,'issue-pager'),refresh=node('button','刷新','layui-btn layui-btn-primary layui-btn-xs');
            heading.append(' ',refresh);container.append(heading,status,rows,pages);detailBody.append(container);var current=1,serial=0,target=selected;
            async function run(p) {
                current=p;var local=++serial;status.textContent='正在加载…';rows.replaceChildren();pages.replaceChildren();
                try {
                    var result=await request('/'+encodeURIComponent(target)+'/'+kind+'?page='+p+'&size=20');
                    if(seq!==detailSeq || local!==serial || !root.isConnected)return;
                    if(p>1 && !result.items.length){return run(Math.max(1,Math.ceil(result.total/20)));}
                    status.textContent=result.items.length?'':'暂无保留记录。';
                    result.items.forEach(function(item){
                        var article=node('article');
                        if(kind==='events') {
                            article.append(node('strong',time(item.occurred_at)+' · '+item.exception_type),node('p',item.method+' '+item.route+' · HTTP '+item.http_status),node('p','请求 ID：'+(item.request_id||'—')+' · 版本：'+item.release_name,'issue-muted'));
                            if(item.client_reported)article.append(node('p','客户端也观察到此请求失败','issue-muted'));
                            if(item.safe_frames)article.append(node('pre',item.safe_frames));
                        } else article.append(node('strong',time(item.created_at)+' · '+item.actor_id),node('p',statuses[item.old_status]+' → '+statuses[item.new_status]+' · 修复版本：'+(item.resolved_release||'—')),node('pre',item.note||'（无备注）'));
                        rows.append(article);
                    });pager(pages,p,result.total,run);
                }catch(error){if(seq===detailSeq && local===serial)status.textContent=error.message;}
            }
            refresh.onclick=function(){run(current);};run(1);
        }
        drawer.addEventListener('keydown',function(e){
            if(e.key==='Escape'){close();return;}
            if(e.key==='Tab'){
                var items=Array.from(drawer.querySelectorAll('button,input,select,textarea')).filter(function(el){return !el.disabled;});
                var first=items[0],last=items[items.length-1];
                if(e.shiftKey && (document.activeElement===first || document.activeElement===drawer)){e.preventDefault();last.focus();}
                else if(!e.shiftKey && document.activeElement===last){e.preventDefault();first.focus();}
            }
        });
        root.querySelector('[data-close]').onclick=close;root.querySelector('[data-reload]').onclick=function(){if(selected)open(selected);};
        root.querySelector('[data-refresh]').onclick=load;root.querySelector('form').onsubmit=function(e){e.preventDefault();page=1;load();};load();
    }
    window.WesiteIssueCenter={mount:mount};
})();
