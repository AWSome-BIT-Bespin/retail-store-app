create table IF NOT EXISTS users (
    id varchar(255) not null,
    email varchar(255) not null unique,
    password_hash varchar(255) not null,
    primary key (id)
);
