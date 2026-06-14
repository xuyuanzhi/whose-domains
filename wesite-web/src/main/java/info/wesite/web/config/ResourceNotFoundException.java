package info.wesite.web.config;

public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;
	
    private final String resourceName;
    private final String resourceId;
    
    public ResourceNotFoundException(String resourceName, String resourceId) {
        super(String.format("%s not found with id: %s", resourceName, resourceId));
        this.resourceName = resourceName;
        this.resourceId = resourceId;
    }
    
    public ResourceNotFoundException(String message) {
        super(message);
        this.resourceName = null;
        this.resourceId = null;
    }
    
    // Getters
    public String getResourceName() { return resourceName; }
    public String getResourceId() { return resourceId; }
}
