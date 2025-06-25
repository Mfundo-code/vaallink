from django.db import models
import uuid
from django.utils import timezone

def generate_session_id():
    return uuid.uuid4().hex[:6].upper()

def default_expiry():
    return timezone.now() + timezone.timedelta(hours=2)  # Extended session time

class ConnectionSession(models.Model):
    session_id = models.CharField(
        max_length=6, 
        unique=True, 
        default=generate_session_id
    )
    host_device_id = models.CharField(max_length=255)
    client_device_id = models.CharField(max_length=255, blank=True, null=True)
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField(default=default_expiry)
    is_active = models.BooleanField(default=True)
    
    def __str__(self):
        return f"{self.session_id}"

    def is_valid(self):
        return self.is_active and timezone.now() < self.expires_at

    @classmethod
    def clean_expired(cls):
        expired = cls.objects.filter(
            expires_at__lt=timezone.now()
        )
        count = expired.count()
        expired.delete()
        return count