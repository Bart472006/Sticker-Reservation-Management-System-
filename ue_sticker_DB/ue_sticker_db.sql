-- UE Campus Pass - Vehicle Sticker System Database Schema
-- Import this file in phpMyAdmin (http://localhost/phpmyadmin) -> Import tab.

CREATE DATABASE IF NOT EXISTS ue_sticker_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE ue_sticker_db;

-- 1. Users Table (Students & Security Admin)
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    department VARCHAR(100) DEFAULT 'College of Engineering',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Vehicle Sticker Applications Table
CREATE TABLE IF NOT EXISTS vehicle_applications (
    id INT AUTO_INCREMENT PRIMARY KEY,
    owner_id VARCHAR(50) NOT NULL,
    owner_name VARCHAR(100) NOT NULL,
    contact_number VARCHAR(50) NULL,
    vehicle_model VARCHAR(120) NOT NULL,
    plate_number VARCHAR(30) NOT NULL,
    pass_type VARCHAR(50) NOT NULL,
    ownership VARCHAR(60) NOT NULL DEFAULT 'My Own Vehicle',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason TEXT NULL,
    approval_date VARCHAR(50) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 3. Inbox & Security Desk Messages Table
CREATE TABLE IF NOT EXISTS messages (
    id INT AUTO_INCREMENT PRIMARY KEY,
    sender_name VARCHAR(120) NOT NULL,
    recipient_id VARCHAR(120) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    is_announcement TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Default Security Admin Account
INSERT IGNORE INTO users (id, name, email, password, role, department)
VALUES ('ADMIN-001', 'UE Security Office', 'admin@ue.edu.ph', 'admin123', 'ADMIN', 'Campus Security & Safety');

-- Sample Student Account (for quick testing)
INSERT IGNORE INTO users (id, name, email, password, role, department)
VALUES ('2021-09876', 'Juan Dela Cruz', 'juan.delacruz@ue.edu.ph', 'student123', 'STUDENT', 'College of Computer Studies & Engineering');
