let currentPage = 1;
const pageSize = 10;

$(document).ready(function() {
    loadContacts(currentPage);
    
    // Refresh button
    $('#refreshBtn').click(function() {
        loadContacts(currentPage);
    });
    
    // Next page button
    $('#nextPage').click(function() {
        currentPage++;
        loadContacts(currentPage);
    });
    
    // Previous page button
    $('#prevPage').click(function() {
        if (currentPage > 1) {
            currentPage--;
            loadContacts(currentPage);
        }
    });
    
    // Select all checkbox
    $('#selectAll').change(function() {
        $('input[name="contactSelect"]').prop('checked', this.checked);
        toggleDeleteButton();
    });
    
    // Individual checkboxes
    $(document).on('change', 'input[name="contactSelect"]', function() {
        toggleDeleteButton();
    });
    
    // Delete selected button
    $('#deleteSelectedBtn').click(function() {
        const selectedIds = [];
        $('input[name="contactSelect"]:checked').each(function() {
            selectedIds.push($(this).val());
        });
        
        if (selectedIds.length === 0) {
            alert('Please select at least one record to delete.');
            return;
        }
        
        if (confirm(`Are you sure you want to delete ${selectedIds.length} record(s)?`)) {
            deleteContacts(selectedIds);
        }
    });
    
    // Delete individual contact
    $(document).on('click', '.delete-contact', function() {
        const id = $(this).data('id');
        if (confirm('Are you sure you want to delete this contact?')) {
            deleteContacts([id]);
        }
    });
});

function loadContacts(page) {
    $.ajax({
        url: `/admin/contacts/list?page=${page}&size=${pageSize}`,
        method: 'GET',
        success: function(response) {
            if (response.code === 0) {
                renderContactsTable(response.data.records);
                updatePagination(response.data.current, response.data.pages);
            } else {
                showNotification(response.msg || 'Failed to load contacts', 'error');
            }
        },
        error: function() {
            showNotification('Error loading contacts', 'error');
        }
    });
}

function renderContactsTable(records) {
    let html = '';
    
    records.forEach(function(contact) {
        html += `
            <tr>
                <td><input type="checkbox" name="contactSelect" value="${contact.id}"></td>
                <td>${contact.id}</td>
                <td>${contact.name}</td>
                <td>${contact.email}</td>
                <td>${contact.subject}</td>
                <td class="message-preview">${truncateText(contact.message, 50)}</td>
                <td>${contact.requestIp}</td>
                <td>${formatDate(contact.createTime)}</td>
                <td>
                    <span class="status-badge ${contact.status === 1 ? 'status-active' : 'status-inactive'}">
                        ${contact.status === 1 ? 'Active' : 'Inactive'}
                    </span>
                </td>
                <td>
                    <button class="btn btn-sm btn-info view-contact" data-id="${contact.id}">View</button>
                    <button class="btn btn-sm btn-danger delete-contact" data-id="${contact.id}">Delete</button>
                </td>
            </tr>
        `;
    });
    
    $('#contactsTableBody').html(html);
}

function truncateText(text, maxLength) {
    if (text.length <= maxLength) {
        return text;
    }
    return text.substr(0, maxLength) + '...';
}

function formatDate(dateString) {
    if (!dateString) return '';
    const date = new Date(dateString);
    return date.toLocaleString();
}

function updatePagination(current, total) {
    currentPage = current;
    $('#currentPage').text(current);
    $('#totalPages').text(total);
    
    // Enable/disable pagination buttons
    $('#prevPage').prop('disabled', current <= 1);
    $('#nextPage').prop('disabled', current >= total);
}

function toggleDeleteButton() {
    const selectedCount = $('input[name="contactSelect"]:checked').length;
    $('#deleteSelectedBtn').prop('disabled', selectedCount === 0);
}

function deleteContacts(ids) {
    $.ajax({
        url: '/admin/contacts/delete',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ids: ids}),
        success: function(response) {
            if (response.code === 0) {
                showNotification(`${ids.length} record(s) deleted successfully`, 'success');
                loadContacts(currentPage); // Reload current page
            } else {
                showNotification(response.msg || 'Failed to delete contacts', 'error');
            }
        },
        error: function() {
            showNotification('Error deleting contacts', 'error');
        }
    });
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
    if (type === 'error') {
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