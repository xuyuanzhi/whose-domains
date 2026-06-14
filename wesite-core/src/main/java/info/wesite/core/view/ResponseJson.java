package info.wesite.core.view;

import java.util.Collection;
import java.util.Collections;

public class ResponseJson<T> {
    
    public static final int CODE_SUCCESS = 0;
    public static final int CODE_FAILURE = 400;
    public static final int CODE_NOAUTH = 401;
    public static final int CODE_ERROR = 500;

    private int code;
    private String msg;
    private Object data;
    private long total = 1L;

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
    
    public long getTotal() {
        return total;
    }
    
    public long getCount() {
    	return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public static <T> ResponseJson<T> success(String msg, T data) {
        ResponseJson<T> ins = new ResponseJson<>();
        ins.setCode(CODE_SUCCESS);
        ins.setMsg(msg);
        ins.setData(data);
        return ins;
    }

    public static <T> ResponseJson<T> success(T t) {
        return ResponseJson.success("success", t);
    }

    public static <T> ResponseJson<T> success(Collection<T> list, long total) {
        ResponseJson<T> ins = new ResponseJson<>();
        ins.setCode(CODE_SUCCESS);
        ins.setMsg("success");
        if (list == null) {
            ins.setData(Collections.emptyList());
        } else {
            ins.setData(list);
        }
        ins.setTotal(total);
        return ins;
    }

    public static <T> ResponseJson<T> success(Collection<T> list) {
        return success(list, list == null ? 0 : list.size());
    }

    public static <T> ResponseJson<T> success() {
        return ResponseJson.success("success", null);
    }

    public static <T> ResponseJson<T> failure(String msg) {
        ResponseJson<T> ins = new ResponseJson<>();
        ins.setCode(CODE_FAILURE);
        ins.setMsg(msg);
        return ins;
    }

    public static <T> ResponseJson<T> response(int code, String msg) {
        ResponseJson<T> ins = new ResponseJson<>();
        ins.setCode(code);
        ins.setMsg(msg);
        return ins;
    }

    public static <T> ResponseJson<T> error(String msg) {
        ResponseJson<T> ins = new ResponseJson<>();
        ins.setCode(CODE_ERROR);
        ins.setMsg(msg);
        return ins;
    }

//    public static class ListData<T> {
//        private Collection<T> list;
//        private long total;
//
//        public ListData(Collection<T> list, long total) {
//            this.list = list;
//            this.total = total;
//        }
//
//        public Collection<T> getList() {
//            return list;
//        }
//
//        public void setList(Collection<T> list) {
//            this.list = list;
//        }
//
//        public long getTotal() {
//            return total;
//        }
//
//        public void setTotal(long total) {
//            this.total = total;
//        }
//
//    }
}
